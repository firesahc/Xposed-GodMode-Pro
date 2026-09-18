package com.kaisar.xposed.godmode.editor;

import android.graphics.Bitmap;
import android.os.RemoteException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.DiagnosticMessages;
import com.kaisar.xposed.godmode.ipc.ImageStore;
import com.kaisar.xposed.godmode.ipc.LeaseHub;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.ipc.RuleServiceContract;
import com.kaisar.xposed.godmode.ipc.ServiceConnection;
import com.kaisar.xposed.godmode.ipc.ServiceDiagnostic;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationRequest;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.UndoRequestParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.orchestrator.RuntimeRuleComparator;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@link IRuleEditor} 的默认实现 — B3 起的真正写入 owner：mutate 主干
 *（幂等 requestId、UNCERTAIN 对账、lease open/close 配对）、reconcile 全家与 undo
 * 全家逐行收归此类，分支顺序与语义零差。
 *
 * <p>协作口：{@link ServiceConnection}（连接核：建连/诊断/终端日志）、
 * {@link LeaseHub}（租约机制：open/close/恢复复用）、{@link ImageStore}
 * （图片库：pipe 双写并发与 FD 只读）、{@link Host}（只读协作口：
 * getRules/getToolbarHiddenItems/世代/编辑 revision 经 Client 现有方法）。
 */
public final class RuleEditorClient implements IRuleEditor {

    /**
     * Client 侧只读协作口：读快照团（getRules/getToolbarHiddenItems/世代）仍归
     * RuleServiceClient。B4 起 pipe 口已收归 {@link ImageStore}。
     */
    public interface Host {
        ActRules getRules(String packageName);
        String getToolbarHiddenItems(String packageName);
        long ruleGeneration();
        long editRevision();
    }

    private static final String TAG = "RuleEditorClient";

    private final ServiceConnection mServiceConnection;
    private final LeaseHub mLeaseHub;
    private final ImageStore mImageStore;
    private final Host mHost;
    private final Gson mGson = new GsonBuilder().create();
    private volatile int mLastMutationStatus = RuleServiceContract.RESULT_NO_CHANGE;

    public RuleEditorClient(ServiceConnection serviceConnection, LeaseHub leaseHub,
                            ImageStore imageStore, Host host) {
        if (serviceConnection == null) throw new IllegalArgumentException("serviceConnection is required");
        if (leaseHub == null) throw new IllegalArgumentException("leaseHub is required");
        if (imageStore == null) throw new IllegalArgumentException("imageStore is required");
        if (host == null) throw new IllegalArgumentException("host is required");
        mServiceConnection = serviceConnection;
        mLeaseHub = leaseHub;
        mImageStore = imageStore;
        mHost = host;
    }

    /** 获取已接线的写入 owner 实例（归 RuleServiceClient 持有，Panel 调用处零改）。 */
    public static RuleEditorClient getInstance() {
        return RuleServiceClient.getDefault().getRuleEditor();
    }

    @Override
    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot) {
        return writeRule(packageName, rule, snapshot, null);
    }

    @Override
    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot,
                             Bitmap modifiedSnapshot) {
        return mutate(packageName, RuleServiceContract.MUTATION_WRITE, rule, snapshot,
                modifiedSnapshot, null);
    }

    @Override
    public RuleMutationResult writeUndoableRule(String packageName, RuleRecord rule,
                                                Bitmap snapshot, Bitmap modifiedSnapshot) {
        return mutateResult(packageName, RuleServiceContract.MUTATION_WRITE, rule, snapshot,
                modifiedSnapshot, null, true);
    }

    @Override
    public boolean updateRule(String packageName, RuleRecord rule) {
        return mutate(packageName, RuleServiceContract.MUTATION_UPDATE, rule, null, null, null);
    }

    @Override
    public boolean deleteRule(String packageName, RuleRecord rule) {
        return mutate(packageName, RuleServiceContract.MUTATION_DELETE, rule, null, null, null);
    }

    @Override
    public boolean deleteRules(String packageName) {
        return mutate(packageName, RuleServiceContract.MUTATION_DELETE_ALL, null, null, null, null);
    }

    public int getLastMutationStatus() {
        return mLastMutationStatus;
    }

    @Override
    public UndoStateParcel getUndoState(String packageName) {
        ServiceConnection.Connection connection = mServiceConnection.ensureConnection();
        if (connection == null) {
            return new UndoStateParcel(RuleServiceContract.RESULT_BUSY, packageName,
                    mHost.editRevision(), 0L, 0, 0L, null,
                    "rule service is unavailable");
        }
        try {
            UndoStateParcel state = connection.service.getUndoState(packageName,
                    mLeaseHub.getLeaseOwner());
            if (state != null && state.status == RuleServiceContract.RESULT_COMMITTED) {
                mServiceConnection.clearDiagnostic();
            } else if (state != null) {
                mServiceConnection.recordResultFailure(state.status, state.message);
            }
            return state;
        } catch (RemoteException e) {
            mServiceConnection.logError("getUndoState", connection, e);
            return new UndoStateParcel(RuleServiceContract.RESULT_UNCERTAIN, packageName,
                    mHost.editRevision(), 0L, 0, 0L, null,
                    "authoritative undo state is uncertain");
        }
    }

    @Override
    public UndoResultParcel undoLatest(String packageName, UndoStateParcel expected) {
        if (packageName == null || expected == null || !packageName.equals(expected.packageName)) {
            return localUndoResult(null, packageName, RuleServiceContract.RESULT_INVALID,
                    expected, "valid expected undo state is required");
        }
        String requestId = UUID.randomUUID().toString();
        UndoResultParcel first = executeUndo(packageName, expected, requestId);
        if (!RuleServiceContract.isUncertain(first.status)) return first;
        return executeUndo(packageName, expected, requestId);
    }

    private UndoResultParcel executeUndo(String packageName, UndoStateParcel expected,
                                         String requestId) {
        String lease = mLeaseHub.openLease(RuleServiceContract.OP_MUTATION, packageName);
        if (lease == null) {
            return localUndoResult(requestId, packageName, RuleServiceContract.RESULT_BUSY,
                    expected, "mutation lease unavailable");
        }
        ServiceConnection.Connection connection = mServiceConnection.ensureConnection();
        if (connection == null) {
            mLeaseHub.closeLease(lease);
            return localUndoResult(requestId, packageName, RuleServiceContract.RESULT_UNCERTAIN,
                    expected, "rule service is unavailable");
        }
        UndoRequestParcel request = new UndoRequestParcel(requestId, lease, packageName,
                expected.editRevision, expected.historyRevision, expected.topSequence);
        try {
            UndoResultParcel result = connection.service.undoLatest(request,
                    mLeaseHub.getLeaseOwner());
            if (result == null) {
                return localUndoResult(requestId, packageName,
                        RuleServiceContract.RESULT_UNCERTAIN, expected,
                        "undo result is missing");
            }
            mLastMutationStatus = result.status;
            if (RuleServiceContract.isTerminalSuccess(result.status)) {
                mServiceConnection.clearDiagnostic();
            } else {
                mServiceConnection.recordResultFailure(result.status, result.message);
            }
            return result;
        } catch (RemoteException e) {
            mServiceConnection.logError("undoLatest", connection, e);
            mLastMutationStatus = RuleServiceContract.RESULT_UNCERTAIN;
            return localUndoResult(requestId, packageName,
                    RuleServiceContract.RESULT_UNCERTAIN, expected,
                    "undo result is uncertain");
        } finally {
            mLeaseHub.closeLease(lease);
        }
    }

    private UndoResultParcel localUndoResult(String requestId, String packageName, int status,
                                             UndoStateParcel state, String message) {
        mLastMutationStatus = status;
        return new UndoResultParcel(status, requestId, packageName,
                mHost.ruleGeneration(), state, message);
    }

    private boolean mutate(String packageName, int operation, RuleRecord rule, Bitmap main,
                           Bitmap modified, String value) {
        RuleMutationResult result = mutateResult(packageName, operation, rule, main, modified,
                value, false);
        return isAccepted(result);
    }

    private RuleMutationResult mutateResult(String packageName, int operation, RuleRecord rule,
                                            Bitmap main, Bitmap modified, String value,
                                            boolean captureUndo) {
        String requestId = UUID.randomUUID().toString();
        boolean temporary = false;
        String lease = mLeaseHub.peekRestoreLease();
        if (lease == null) {
            lease = mLeaseHub.openLease(RuleServiceContract.OP_MUTATION, packageName);
            temporary = true;
        }
        if (lease == null) {
            mLastMutationStatus = RuleServiceContract.RESULT_BUSY;
            mServiceConnection.logMutationTerminal(operation, packageName, requestId,
                    mLastMutationStatus, "lease_unavailable");
            return localMutationResult(requestId, packageName, mLastMutationStatus,
                    "mutation lease unavailable");
        }
        // B4：pipe 经 ImageStore 直调，Host 只剩只读协作口。
        ImageStore.PipeAsset mainPipe = mImageStore.openPipe(main);
        ImageStore.PipeAsset modifiedPipe = mImageStore.openPipe(modified);
        if ((main != null && mainPipe == null) || (modified != null && modifiedPipe == null)) {
            ImageStore.closePipe(mainPipe);
            ImageStore.closePipe(modifiedPipe);
            ImageStore.awaitPipe(mainPipe);
            ImageStore.awaitPipe(modifiedPipe);
            if (temporary) mLeaseHub.closeLease(lease);
            mLastMutationStatus = RuleServiceContract.RESULT_WRITE_FAILED;
            mServiceConnection.logMutationTerminal(operation, packageName, requestId,
                    mLastMutationStatus, "image_pipe_unavailable");
            return localMutationResult(requestId, packageName, mLastMutationStatus,
                    "image pipe unavailable");
        }
        RuleMutationRequest request = new RuleMutationRequest(operation, requestId, lease,
                packageName, rule == null ? null : mGson.toJson(rule),
                mainPipe == null ? null : mainPipe.readEnd,
                modifiedPipe == null ? null : modifiedPipe.readEnd, value, captureUndo);
        ServiceConnection.Connection c = mServiceConnection.ensureConnection();
        boolean accepted = false;
        boolean uncertain = false;
        RuleMutationResult authoritative = null;
        try {
            if (c != null) {
                authoritative = c.service.mutate(request, mLeaseHub.getLeaseOwner());
                mLastMutationStatus = authoritative == null
                        ? RuleServiceContract.RESULT_UNCERTAIN : authoritative.status;
                uncertain = authoritative == null;
                accepted = isAccepted(authoritative);
                if (accepted) mServiceConnection.clearDiagnostic();
                if (!accepted) {
                    String mutationError = authoritative == null
                            ? DiagnosticMessages.MUTATE_RESULT_MISSING_DETAIL
                            : authoritative.message;
                    if (authoritative == null) {
                        mServiceConnection.recordResultFailure(RuleServiceContract.RESULT_UNCERTAIN, mutationError);
                    } else {
                        mServiceConnection.recordResultFailure(authoritative.status, mutationError);
                    }
                }
            } else {
                mLastMutationStatus = RuleServiceContract.RESULT_REJECTED;
            }
        } catch (RemoteException e) {
            if (c != null) mServiceConnection.logError("mutate", c, e);
            uncertain = true;
            mLastMutationStatus = RuleServiceContract.RESULT_UNCERTAIN;
            mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.COMMIT_UNCERTAIN,
                    String.format(Locale.US, DiagnosticMessages.MUTATE_UNCERTAIN_REQUEST_ID_DETAIL, requestId)));
        }
        finally {
            if (mainPipe != null) mainPipe.closeRead();
            if (modifiedPipe != null) modifiedPipe.closeRead();
            ImageStore.awaitPipe(mainPipe);
            ImageStore.awaitPipe(modifiedPipe);
            Throwable pipeFailure = ImageStore.firstFailure(mainPipe, modifiedPipe);
            if (pipeFailure != null) {
                Logger.w(TAG, "mutation image pipe failed operation="
                        + ServiceConnection.mutationOperationName(operation) + " package=" + packageName
                        + " requestId=" + requestId, pipeFailure);
                if (!accepted && !uncertain) {
                    mLastMutationStatus = RuleServiceContract.RESULT_WRITE_FAILED;
                    mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            String.format(Locale.US, DiagnosticMessages.IMAGE_PIPE_WRITE_FAILED_DETAIL,
                                    pipeFailure.getMessage())));
                }
            }
            ImageStore.closePipe(mainPipe);
            ImageStore.closePipe(modifiedPipe);
            ServiceDiagnostic mutationDiagnostic = mServiceConnection.getServiceDiagnostic();
            String mutationError = mServiceConnection.getLastError();
            if (temporary) mLeaseHub.closeLease(lease);
            if (!accepted && mutationDiagnostic != null) {
                mServiceConnection.restoreDiagnostic(mutationDiagnostic, mutationError);
            }
        }
        if (uncertain) {
            if (captureUndo) {
                UndoStateParcel state = getUndoState(packageName);
                if (state != null && requestId.equals(state.topSourceRequestId)) {
                    mLastMutationStatus = RuleServiceContract.RESULT_COMMITTED;
                    mServiceConnection.clearDiagnostic();
                    mServiceConnection.logMutationTerminal(operation, packageName, requestId,
                            mLastMutationStatus, "undo_history_reconciled");
                    return new RuleMutationResult(RuleServiceContract.RESULT_COMMITTED,
                            requestId, packageName, mHost.ruleGeneration(), null, state,
                            "committed; response reconciled from undo history");
                }
                mServiceConnection.logMutationTerminal(operation, packageName, requestId,
                        RuleServiceContract.RESULT_UNCERTAIN, "undo_history_inconclusive");
                return localMutationResult(requestId, packageName,
                        RuleServiceContract.RESULT_UNCERTAIN,
                        "mutation result is uncertain; authoritative history did not confirm it");
            }
            int reconciled = reconcileUncertain(packageName, operation, rule,
                    main != null, modified != null, value);
            mLastMutationStatus = reconciled;
            if (reconciled == RuleServiceContract.RESULT_COMMITTED) {
                mServiceConnection.clearDiagnostic();
                mServiceConnection.logMutationTerminal(operation, packageName, requestId, reconciled,
                        "reconciled_committed");
                return localMutationResult(requestId, packageName, reconciled,
                        "committed; response reconciled from snapshot");
            }
            if (reconciled == RuleServiceContract.RESULT_REJECTED) {
                mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        String.format(Locale.US, DiagnosticMessages.MUTATE_READBACK_NOT_FOUND_REQUEST_ID_DETAIL,
                                requestId)));
            } else {
                mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.COMMIT_UNCERTAIN,
                        String.format(Locale.US, DiagnosticMessages.MUTATE_RECONCILE_UNKNOWN_REQUEST_ID_DETAIL,
                                requestId)));
            }
            mServiceConnection.logMutationTerminal(operation, packageName, requestId, reconciled,
                    "reconciled_inconclusive");
            return localMutationResult(requestId, packageName, reconciled,
                    "mutation result remains uncertain after snapshot reconciliation");
        }
        mServiceConnection.logMutationTerminal(operation, packageName, requestId, mLastMutationStatus,
                accepted ? "accepted" : "rejected");
        return authoritative == null
                ? localMutationResult(requestId, packageName, mLastMutationStatus,
                "mutation result missing") : authoritative;
    }

    private RuleMutationResult localMutationResult(String requestId, String packageName,
                                                    int status, String message) {
        return new RuleMutationResult(status, requestId, packageName,
                mHost.ruleGeneration(), null, null, message);
    }

    private static boolean isAccepted(RuleMutationResult result) {
        return result != null && (result.status == RuleServiceContract.RESULT_COMMITTED
                || result.status == RuleServiceContract.RESULT_NO_CHANGE);
    }

    private int reconcileUncertain(String packageName, int operation, RuleRecord rule,
                                   boolean hadMainImage, boolean hadModifiedImage,
                                   String value) {
        if (operation == RuleServiceContract.MUTATION_SET_TOOLBAR) {
            return reconcileToolbarValue(
                    mHost.getToolbarHiddenItems(RuleServiceContract.GLOBAL_SCOPE), value);
        }
        ActRules rules = mHost.getRules(packageName);
        if (rules == null) return RuleServiceContract.RESULT_UNCERTAIN;
        switch (operation) {
            case RuleServiceContract.MUTATION_WRITE:
            case RuleServiceContract.MUTATION_UPDATE:
                return reconcileUpsert(rules, rule, hadMainImage, hadModifiedImage);
            case RuleServiceContract.MUTATION_DELETE:
                return reconcileDelete(rules, rule);
            case RuleServiceContract.MUTATION_DELETE_ALL:
                return reconcileDeleteAll(rules);
            default:
                return RuleServiceContract.RESULT_UNCERTAIN;
        }
    }

    /** 工具栏回读一致即提交成功，读不到即不确定，对不上即拒绝。 */
    private int reconcileToolbarValue(String current, String value) {
        if (current == null) return RuleServiceContract.RESULT_UNCERTAIN;
        return current.equals(value == null ? "" : value)
                ? RuleServiceContract.RESULT_COMMITTED
                : RuleServiceContract.RESULT_REJECTED;
    }

    /** 写/改后能在权威快照中找到提交态规则即成功，否则拒绝。 */
    private int reconcileUpsert(ActRules rules, RuleRecord rule,
                                boolean hadMainImage, boolean hadModifiedImage) {
        return containsCommittedRule(rules, rule, hadMainImage, hadModifiedImage)
                ? RuleServiceContract.RESULT_COMMITTED
                : RuleServiceContract.RESULT_REJECTED;
    }

    /** 删除后槽位仍在即拒绝，否则提交成功。 */
    private int reconcileDelete(ActRules rules, RuleRecord rule) {
        return containsSlot(rules, rule) ? RuleServiceContract.RESULT_REJECTED
                : RuleServiceContract.RESULT_COMMITTED;
    }

    /** 全删后为空即成功，否则拒绝。 */
    private int reconcileDeleteAll(ActRules rules) {
        return isEmpty(rules) ? RuleServiceContract.RESULT_COMMITTED
                : RuleServiceContract.RESULT_REJECTED;
    }

    public static boolean containsCommittedRule(ActRules rules, RuleRecord expected,
                                         boolean hadMainImage, boolean hadModifiedImage) {
        if (rules == null || expected == null) return false;
        for (List<RuleRecord> activityRules : rules.values()) {
            if (activityRules == null) continue;
            for (RuleRecord actual : activityRules) {
                if (actual == null || !actual.slotKey(actual.packageName)
                        .equals(expected.slotKey(expected.packageName))) continue;
                RuleRecord normalized = expected;
                if (hadMainImage) normalized = normalized.withImagePath(actual.imagePath);
                if (hadModifiedImage) {
                    normalized = normalized.withModifyImagePath(actual.getModImagePath());
                }
                if (RuntimeRuleComparator.contentEquals(actual, normalized)) return true;
            }
        }
        return false;
    }

    public static boolean containsSlot(ActRules rules, RuleRecord expected) {
        if (rules == null || expected == null) return false;
        for (List<RuleRecord> activityRules : rules.values()) {
            if (activityRules == null) continue;
            for (RuleRecord actual : activityRules) {
                if (actual != null && actual.slotKey(actual.packageName)
                        .equals(expected.slotKey(expected.packageName))) return true;
            }
        }
        return false;
    }

    private static boolean isEmpty(ActRules rules) {
        for (List<RuleRecord> activityRules : rules.values()) {
            if (activityRules != null && !activityRules.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public String getFailureMessage() {
        return mServiceConnection.getServiceFailureMessage();
    }

    @Override
    public String getToolbarHiddenItems(String packageName) {
        return mHost.getToolbarHiddenItems(packageName);
    }

    @Override
    public boolean setToolbarHiddenItems(String items) {
        return mutate(RuleServiceContract.GLOBAL_SCOPE, RuleServiceContract.MUTATION_SET_TOOLBAR,
                null, null, null, items);
    }
}
