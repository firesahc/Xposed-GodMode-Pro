package com.kaisar.xposed.godmode.ipc;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SharedMemory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.engine.util.Closeables;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.contract.ILeaseOwner;
import com.kaisar.xposed.godmode.ipc.contract.IRuleObserver;
import com.kaisar.xposed.godmode.ipc.contract.ObserverRegistrationParcel;
import com.kaisar.xposed.godmode.ipc.contract.OperationLeaseParcel;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationRequest;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.RuleSnapshotParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoRequestParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.orchestrator.RuntimeRuleComparator;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.util.TaskExecutor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Single client facade for the canonical 6.10 rule service. */
public final class RuleServiceClient {
    private static final String TAG = "RuleServiceClient";
    private static volatile RuleServiceClient instance;

    private final ServiceConnection mServiceConnection = new ServiceConnection();
    private final LogBridge mLogBridge;
    private final Gson mGson = new GsonBuilder().create();
    private final CopyOnWriteArrayList<ObserverSubscription> mObserverSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong mRuleGeneration = new AtomicLong();
    private final ClientEditState mEditState = new ClientEditState();
    private final ILeaseOwner mLeaseOwner = new ILeaseOwner.Stub() {
        @Override public void onLeaseRevoked(int reason) {
            boolean hadRestoreLease = mRestoreLease != null;
            boolean hadBackupLease = mBackupLease != null;
            boolean wasEditEnabled = mEditState.isEnabled();
            long editRevision = mEditState.revision();
            long epoch = mServiceConnection.getConnectionEpoch();
            mRestoreLease = null;
            mBackupLease = null;
            mEditState.reset();
            Logger.w(TAG, "operation lease revoked reason=" + reason
                    + " epoch=" + epoch + " editEnabled=" + wasEditEnabled
                    + " editRevision=" + editRevision
                    + " hadRestoreLease=" + hadRestoreLease
                    + " hadBackupLease=" + hadBackupLease);
        }
    };
    private volatile String mRestoreLease;
    private volatile String mBackupLease;
    private volatile int mLastMutationStatus = RuleServiceContract.RESULT_NO_CHANGE;

    private RuleServiceClient() {
        mLogBridge = new LogBridge(mServiceConnection);
        mServiceConnection.setConnectionListener(new ServiceConnection.ConnectionListener() {
            @Override public void onConnectionCleared() {
                mRestoreLease = null;
                mBackupLease = null;
                mEditState.reset();
                mRuleGeneration.set(0L);
                for (ObserverSubscription subscription : mObserverSubscriptions) {
                    subscription.clearRemote();
                }
            }

            @Override public void onConnectionEstablished(ServiceConnection.Connection connection) {
                reregisterObservers(connection);
            }
        });
        mServiceConnection.setLogBridge(mLogBridge);
    }

    /** Installs the process-side durable sink for Logger and XServiceManager diagnostics. */
    public void installProcessLogging(String packageName) {
        mLogBridge.installProcessLogging(packageName);
    }

    public LogBridge getLogBridge() {
        return mLogBridge;
    }

    public static RuleServiceClient getDefault() {
        RuleServiceClient result = instance;
        if (result == null) {
            synchronized (RuleServiceClient.class) {
                result = instance;
                if (result == null) {
                    result = new RuleServiceClient();
                    instance = result;
                }
            }
        }
        return result;
    }

    public String getLastError() { return mServiceConnection.getLastError(); }
    public ServiceDiagnostic getServiceDiagnostic() { return mServiceConnection.getServiceDiagnostic(); }
    public String getServiceFailureMessage() { return mServiceConnection.getServiceFailureMessage(); }
    public int getServiceState() { return mServiceConnection.getServiceState(); }
    public boolean isReady() { return mServiceConnection.isReady(); }
    public boolean isConnected() { return mServiceConnection.isConnected(); }
    public boolean hasReadyConnection() { return mServiceConnection.hasReadyConnection(); }
    public boolean awaitReady(long timeoutMs) { return mServiceConnection.awaitReady(timeoutMs); }

    public void addBinderDeathListener(Runnable listener) {
        mServiceConnection.addBinderDeathListener(listener);
    }
    public void removeBinderDeathListener(Runnable listener) {
        mServiceConnection.removeBinderDeathListener(listener);
    }

    public boolean hasLight() { return mServiceConnection.hasLight(); }

    private ServiceConnection.Connection ensureConnection() {
        return mServiceConnection.ensureConnection();
    }

    private void recordDiagnostic(ServiceDiagnostic diagnostic) {
        mServiceConnection.recordDiagnostic(diagnostic);
    }

    private void clearDiagnostic() {
        mServiceConnection.clearDiagnostic();
    }

    private void recordResultFailure(int status, String detail) {
        mServiceConnection.recordResultFailure(status, detail);
    }

    private void logError(String method, ServiceConnection.Connection connection, RemoteException e) {
        mServiceConnection.logError(method, connection, e);
    }

    private void reregisterObservers(ServiceConnection.Connection connection) {
        for (ObserverSubscription subscription : mObserverSubscriptions) {
            try { registerObserver(connection, subscription); }
            catch (RemoteException e) { Logger.w(TAG, "observer re-register failed", e); }
        }
    }

    private void registerObserver(ServiceConnection.Connection connection, ObserverSubscription subscription)
            throws RemoteException {
        ObserverRelay relay = new ObserverRelay(connection.epoch, subscription.observer);
        ObserverRegistrationParcel registration = connection.service.addObserver(
                subscription.packageName, relay);
        if (registration == null
                || (registration.status != RuleServiceContract.RESULT_COMMITTED
                && registration.status != RuleServiceContract.RESULT_NO_CHANGE)) {
            Logger.w(TAG, "observer registration rejected package=" + subscription.packageName
                    + " status=" + (registration == null ? "null" : registration.status)
                    + " epoch=" + connection.epoch);
            return;
        }
        subscription.bind(connection.epoch, relay);
        if (acceptEditState(connection.epoch, registration.editEnabled,
                registration.editRevision)) {
            subscription.observer.onEditModeChanged(registration.editEnabled,
                    registration.editRevision, connection.epoch);
        }
        if (acceptRuleGeneration(connection.epoch, registration.ruleGeneration)) {
            subscription.observer.onRulesInvalidated(subscription.packageName,
                    registration.ruleGeneration, connection.epoch);
        }
    }

    public synchronized boolean setEditMode(boolean enable) {
        if (enable) {
            if (!mEditState.canRequestEnable()) {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.OPERATION_BUSY,
                        DiagnosticMessages.EDIT_CLOSE_PENDING_COMMIT_DETAIL));
                return false;
            }
            mEditState.clearStaleDisabledLease();
            if (mEditState.leaseToken() == null) {
                String token = openLease(RuleServiceContract.OP_EDIT, null);
                if (token == null) return false;
                mEditState.setLeaseToken(token);
            }
            return true;
        }
        String token = mEditState.leaseToken();
        if (token != null) {
            if (!closeLease(token)) return false;
            // The close reply and observer callback cross different Binder channels. Keep the
            // client in CLOSING until the authoritative disabled revision arrives.
            mEditState.markClosing(token);
            return true;
        }
        return !enable;
    }

    public boolean isEditModeEnabled() {
        return hasReadyConnection() && mEditState.isEnabled();
    }

    public boolean isEditStateKnown() {
        return hasReadyConnection() && mEditState.isKnown();
    }

    public boolean isEditModeClosing() {
        return hasReadyConnection() && mEditState.isClosing();
    }

    private String openLease(int type, String packageName) {
        ServiceConnection.Connection c = ensureConnection(); if (c == null) return null;
        try {
            OperationLeaseParcel lease = c.service.openOperation(type, packageName, mLeaseOwner);
            if (lease != null && lease.status == RuleServiceContract.RESULT_COMMITTED) {
                clearDiagnostic();
                return lease.token;
            }
            if (lease == null) {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        DiagnosticMessages.OPERATION_LEASE_MISSING_DETAIL));
            } else {
                recordResultFailure(lease.status, lease.message);
            }
            return null;
        } catch (RemoteException e) { logError("openOperation", c, e); return null; }
    }

    private boolean closeLease(String token) {
        ServiceConnection.Connection c = ensureConnection(); if (c == null) return false;
        try {
            OperationLeaseParcel result = c.service.closeOperation(token, mLeaseOwner);
            boolean closed = result != null
                    && (result.status == RuleServiceContract.RESULT_COMMITTED
                    || result.status == RuleServiceContract.RESULT_NO_CHANGE);
            if (closed) clearDiagnostic();
            if (!closed) {
                if (result == null) {
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            DiagnosticMessages.OPERATION_CLOSE_RESULT_MISSING_DETAIL));
                } else {
                    recordResultFailure(result.status, result.message);
                }
                if (result != null && result.status == RuleServiceContract.RESULT_BUSY
                        && token.equals(mEditState.leaseToken())) {
                    mEditState.markClosing(token);
                }
            }
            return closed;
        }
        catch (RemoteException e) { logError("closeOperation", c, e); return false; }
    }

    public void addObserver(String packageName, ObserverCallback observer) {
        if (packageName == null || observer == null) {
            Logger.w(TAG, "addObserver rejected reason=missing_package_or_callback");
            return;
        }
        ObserverSubscription subscription = findSubscription(packageName, observer);
        if (subscription == null) {
            subscription = new ObserverSubscription(packageName, observer);
            mObserverSubscriptions.add(subscription);
        }
        ServiceConnection.Connection c = ensureConnection();
        if (c == null) {
            Logger.w(TAG, "addObserver deferred package=" + packageName
                    + " reason=service_unavailable");
            return;
        }
        if (subscription.remoteForEpoch(c.epoch) != null) {
            Logger.d(TAG, "addObserver ignored package=" + packageName
                    + " reason=already_registered epoch=" + c.epoch);
            return;
        }
        try { registerObserver(c, subscription); }
        catch (RemoteException e) { logError("addObserver", c, e); }
    }

    public void removeObserver(String packageName, ObserverCallback observer) {
        if (packageName == null || observer == null) {
            Logger.d(TAG, "removeObserver ignored reason=missing_package_or_callback");
            return;
        }
        ObserverSubscription subscription = findSubscription(packageName, observer);
        if (subscription == null) {
            Logger.d(TAG, "removeObserver ignored package=" + packageName
                    + " reason=not_registered");
            return;
        }
        mObserverSubscriptions.remove(subscription);
        ServiceConnection.Connection c = ensureConnection();
        if (c == null) {
            Logger.d(TAG, "removeObserver local_only package=" + packageName
                    + " reason=service_unavailable");
            return;
        }
        IRuleObserver relay = subscription.remoteForEpoch(c.epoch);
        if (relay == null) {
            Logger.d(TAG, "removeObserver local_only package=" + packageName
                    + " reason=no_remote_registration epoch=" + c.epoch);
            return;
        }
        try { c.service.removeObserver(packageName, relay); }
        catch (RemoteException e) { logError("removeObserver", c, e); }
    }

    private ObserverSubscription findSubscription(String packageName, ObserverCallback observer) {
        for (ObserverSubscription subscription : mObserverSubscriptions) {
            if (subscription.matches(packageName, observer)) return subscription;
        }
        return null;
    }

    private boolean acceptEditState(long epoch, boolean enabled, long revision) {
        synchronized (mServiceConnection) {
            return isCurrentEpochLocked(epoch) && mEditState.accept(enabled, revision);
        }
    }

    private boolean acceptRuleGeneration(long epoch, long generation) {
        if (!isCurrentEpoch(epoch)) return false;
        return generation >= mRuleGeneration.get() && isCurrentEpoch(epoch);
    }

    public boolean isCurrentEditEvent(long epoch, long revision) {
        return ClientEventOrder.isCurrent(epoch, mServiceConnection.getConnectionEpoch(),
                revision, mEditState.revision()) && isCurrentEpoch(epoch);
    }

    public boolean isCurrentRuleEvent(long epoch, long generation) {
        return ClientEventOrder.isCurrent(epoch, mServiceConnection.getConnectionEpoch(),
                generation, mRuleGeneration.get()) && isCurrentEpoch(epoch);
    }

    private boolean isCurrentEpoch(long epoch) {
        return mServiceConnection.isCurrentEpoch(epoch);
    }

    private boolean isCurrentEpochLocked(long epoch) {
        return mServiceConnection.isCurrentEpoch(epoch);
    }

    public AppRules getAllRules() {
        return getAllRulesAtLeast(0L);
    }

    public AppRules getAllRulesAtLeast(long minimumGeneration) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ServiceConnection.Connection c = ensureConnection(); if (c == null) return null;
            try {
                RuleSnapshotParcel snapshot = c.service.getAllRulesSnapshot();
                if (snapshot == null || snapshot.status == RuleServiceContract.SNAPSHOT_UNAVAILABLE) {
                    Logger.d(TAG, "getAllRules snapshot unavailable attempt=" + (attempt + 1));
                    closeSnapshotMemory(snapshot);
                    return null;
                }
                if (snapshot.generation < minimumGeneration) {
                    Logger.d(TAG, "getAllRules snapshot stale generation=" + snapshot.generation
                            + " minimum=" + minimumGeneration);
                    closeSnapshotMemory(snapshot);
                    continue;
                }
                AppRules rules = readSnapshot(snapshot, AppRules.class);
                if (rules == null) {
                    Logger.w(TAG, "getAllRules snapshot decoded null generation="
                            + snapshot.generation);
                    return null;
                }
                mRuleGeneration.accumulateAndGet(snapshot.generation, Math::max);
                return rules;
            } catch (RemoteException | RuntimeException e) {
                logError("getAllRules", c, ServiceConnection.asRemote(e)); return null;
            }
        }
        Logger.w(TAG, "getAllRules could not satisfy minimum generation=" + minimumGeneration);
        return null;
    }

    public ActRules getRules(String packageName) { return getRulesAtLeast(packageName, 0L); }

    public ActRules getRulesAtLeast(String packageName, long minimumGeneration) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ServiceConnection.Connection c = ensureConnection(); if (c == null) return null;
            try {
                RuleSnapshotParcel snapshot = c.service.getRulesSnapshot(packageName);
                if (snapshot == null || snapshot.status == RuleServiceContract.SNAPSHOT_UNAVAILABLE) {
                    Logger.d(TAG, "getRules snapshot unavailable package=" + packageName
                            + " attempt=" + (attempt + 1));
                    closeSnapshotMemory(snapshot);
                    return null;
                }
                if (snapshot.generation < minimumGeneration) {
                    Logger.d(TAG, "getRules snapshot stale package=" + packageName
                            + " generation=" + snapshot.generation
                            + " minimum=" + minimumGeneration);
                    closeSnapshotMemory(snapshot);
                    continue;
                }
                ActRules rules = readSnapshot(snapshot, ActRules.class);
                if (rules == null) {
                    Logger.w(TAG, "getRules snapshot decoded null package=" + packageName
                            + " generation=" + snapshot.generation);
                    return null;
                }
                mRuleGeneration.accumulateAndGet(snapshot.generation, Math::max);
                return rules;
            } catch (RemoteException | RuntimeException e) {
                logError("getRules", c, ServiceConnection.asRemote(e));
                return null;
            }
        }
        Logger.w(TAG, "getRules could not satisfy package=" + packageName
                + " minimumGeneration=" + minimumGeneration);
        return null;
    }

    private <T> T readSnapshot(RuleSnapshotParcel snapshot, Class<T> type) {
        if (snapshot == null) {
            Logger.w(TAG, "snapshot read rejected reason=null_snapshot");
            return null;
        }
        if (snapshot.memory == null) {
            Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                    + " generation=" + snapshot.generation + " reason=no_memory");
            return null;
        }
        ByteBuffer buffer = null;
        try {
            if (snapshot.payloadLength < 0 || snapshot.payloadLength > 8 * 1024 * 1024) {
                Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                        + " generation=" + snapshot.generation + " reason=invalid_length");
                return null;
            }
            buffer = snapshot.memory.mapReadOnly();
            if (snapshot.payloadLength > buffer.remaining()) {
                Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                        + " generation=" + snapshot.generation + " reason=short_buffer");
                return null;
            }
            byte[] bytes = new byte[snapshot.payloadLength];
            buffer.get(bytes);
            if (!sha256(bytes).equalsIgnoreCase(snapshot.sha256)) {
                Logger.w(TAG, "snapshot read rejected scope=" + snapshot.packageName
                        + " generation=" + snapshot.generation + " reason=checksum_mismatch");
                return null;
            }
            return mGson.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            Logger.w(TAG, "snapshot read failed scope=" + snapshot.packageName
                    + " generation=" + snapshot.generation, e);
            throw new IllegalStateException(DiagnosticMessages.SNAPSHOT_READ_FAILED_EXCEPTION, e);
        } finally {
            if (buffer != null) SharedMemory.unmap(buffer);
            closeSnapshotMemory(snapshot);
        }
    }

    private void closeSnapshotMemory(RuleSnapshotParcel snapshot) {
        if (snapshot == null || snapshot.memory == null) return;
        try {
            snapshot.memory.close();
        } catch (Exception e) {
            Logger.w(TAG, "snapshot memory close failed scope=" + snapshot.packageName
                    + " generation=" + snapshot.generation, e);
        }
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot) {
        return writeRule(packageName, rule, snapshot, null);
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot, Bitmap modifiedSnapshot) {
        return mutate(packageName, RuleServiceContract.MUTATION_WRITE, rule, snapshot,
                modifiedSnapshot, null);
    }

    public RuleMutationResult writeUndoableRule(String packageName, RuleRecord rule,
                                                Bitmap snapshot, Bitmap modifiedSnapshot) {
        return mutateResult(packageName, RuleServiceContract.MUTATION_WRITE, rule, snapshot,
                modifiedSnapshot, null, true);
    }
    public boolean updateRule(String packageName, RuleRecord rule) {
        return mutate(packageName, RuleServiceContract.MUTATION_UPDATE, rule, null, null, null);
    }
    public boolean deleteRule(String packageName, RuleRecord rule) {
        return mutate(packageName, RuleServiceContract.MUTATION_DELETE, rule, null, null, null);
    }
    public boolean deleteRules(String packageName) {
        return mutate(packageName, RuleServiceContract.MUTATION_DELETE_ALL, null, null, null, null);
    }

    public int getLastMutationStatus() {
        return mLastMutationStatus;
    }

    public UndoStateParcel getUndoState(String packageName) {
        ServiceConnection.Connection connection = ensureConnection();
        if (connection == null) {
            return new UndoStateParcel(RuleServiceContract.RESULT_BUSY, packageName,
                    mEditState.revision(), 0L, 0, 0L, null,
                    "rule service is unavailable");
        }
        try {
            UndoStateParcel state = connection.service.getUndoState(packageName, mLeaseOwner);
            if (state != null && state.status == RuleServiceContract.RESULT_COMMITTED) {
                clearDiagnostic();
            } else if (state != null) {
                recordResultFailure(state.status, state.message);
            }
            return state;
        } catch (RemoteException e) {
            logError("getUndoState", connection, e);
            return new UndoStateParcel(RuleServiceContract.RESULT_UNCERTAIN, packageName,
                    mEditState.revision(), 0L, 0, 0L, null,
                    "authoritative undo state is uncertain");
        }
    }

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
        String lease = openLease(RuleServiceContract.OP_MUTATION, packageName);
        if (lease == null) {
            return localUndoResult(requestId, packageName, RuleServiceContract.RESULT_BUSY,
                    expected, "mutation lease unavailable");
        }
        ServiceConnection.Connection connection = ensureConnection();
        if (connection == null) {
            closeLease(lease);
            return localUndoResult(requestId, packageName, RuleServiceContract.RESULT_UNCERTAIN,
                    expected, "rule service is unavailable");
        }
        UndoRequestParcel request = new UndoRequestParcel(requestId, lease, packageName,
                expected.editRevision, expected.historyRevision, expected.topSequence);
        try {
            UndoResultParcel result = connection.service.undoLatest(request, mLeaseOwner);
            if (result == null) {
                return localUndoResult(requestId, packageName,
                        RuleServiceContract.RESULT_UNCERTAIN, expected,
                        "undo result is missing");
            }
            mLastMutationStatus = result.status;
            if (RuleServiceContract.isTerminalSuccess(result.status)) {
                clearDiagnostic();
            } else {
                recordResultFailure(result.status, result.message);
            }
            return result;
        } catch (RemoteException e) {
            logError("undoLatest", connection, e);
            mLastMutationStatus = RuleServiceContract.RESULT_UNCERTAIN;
            return localUndoResult(requestId, packageName,
                    RuleServiceContract.RESULT_UNCERTAIN, expected,
                    "undo result is uncertain");
        } finally {
            closeLease(lease);
        }
    }

    private UndoResultParcel localUndoResult(String requestId, String packageName, int status,
                                             UndoStateParcel state, String message) {
        mLastMutationStatus = status;
        return new UndoResultParcel(status, requestId, packageName,
                mRuleGeneration.get(), state, message);
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
        String lease = mRestoreLease;
        if (lease == null) {
            lease = openLease(RuleServiceContract.OP_MUTATION, packageName);
            temporary = true;
        }
        if (lease == null) {
            mLastMutationStatus = RuleServiceContract.RESULT_BUSY;
            mServiceConnection.logMutationTerminal(operation, packageName, requestId,
                    mLastMutationStatus, "lease_unavailable");
            return localMutationResult(requestId, packageName, mLastMutationStatus,
                    "mutation lease unavailable");
        }
        PipeAsset mainPipe = openPipe(main);
        PipeAsset modifiedPipe = openPipe(modified);
        if ((main != null && mainPipe == null) || (modified != null && modifiedPipe == null)) {
            closePipe(mainPipe);
            closePipe(modifiedPipe);
            awaitPipe(mainPipe);
            awaitPipe(modifiedPipe);
            if (temporary) closeLease(lease);
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
        ServiceConnection.Connection c = ensureConnection();
        boolean accepted = false;
        boolean uncertain = false;
        RuleMutationResult authoritative = null;
        try {
            if (c != null) {
                authoritative = c.service.mutate(request, mLeaseOwner);
                mLastMutationStatus = authoritative == null
                        ? RuleServiceContract.RESULT_UNCERTAIN : authoritative.status;
                uncertain = authoritative == null;
                accepted = isAccepted(authoritative);
                if (accepted) clearDiagnostic();
                if (!accepted) {
                    String mutationError = authoritative == null
                            ? DiagnosticMessages.MUTATE_RESULT_MISSING_DETAIL
                            : authoritative.message;
                    if (authoritative == null) {
                        recordResultFailure(RuleServiceContract.RESULT_UNCERTAIN, mutationError);
                    } else {
                        recordResultFailure(authoritative.status, mutationError);
                    }
                }
            } else {
                mLastMutationStatus = RuleServiceContract.RESULT_REJECTED;
            }
        } catch (RemoteException e) {
            if (c != null) logError("mutate", c, e);
            uncertain = true;
            mLastMutationStatus = RuleServiceContract.RESULT_UNCERTAIN;
            recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.COMMIT_UNCERTAIN,
                    String.format(Locale.US, DiagnosticMessages.MUTATE_UNCERTAIN_REQUEST_ID_DETAIL, requestId)));
        }
        finally {
            if (mainPipe != null) mainPipe.closeRead();
            if (modifiedPipe != null) modifiedPipe.closeRead();
            awaitPipe(mainPipe);
            awaitPipe(modifiedPipe);
            Throwable pipeFailure = firstFailure(mainPipe, modifiedPipe);
            if (pipeFailure != null) {
                Logger.w(TAG, "mutation image pipe failed operation="
                        + ServiceConnection.mutationOperationName(operation) + " package=" + packageName
                        + " requestId=" + requestId, pipeFailure);
                if (!accepted && !uncertain) {
                    mLastMutationStatus = RuleServiceContract.RESULT_WRITE_FAILED;
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            String.format(Locale.US, DiagnosticMessages.IMAGE_PIPE_WRITE_FAILED_DETAIL,
                                    pipeFailure.getMessage())));
                }
            }
            closePipe(mainPipe);
            closePipe(modifiedPipe);
            ServiceDiagnostic mutationDiagnostic = mServiceConnection.getServiceDiagnostic();
            String mutationError = mServiceConnection.getLastError();
            if (temporary) closeLease(lease);
            if (!accepted && mutationDiagnostic != null) {
                mServiceConnection.restoreDiagnostic(mutationDiagnostic, mutationError);
            }
        }
        if (uncertain) {
            if (captureUndo) {
                UndoStateParcel state = getUndoState(packageName);
                if (state != null && requestId.equals(state.topSourceRequestId)) {
                    mLastMutationStatus = RuleServiceContract.RESULT_COMMITTED;
                    clearDiagnostic();
                    mServiceConnection.logMutationTerminal(operation, packageName, requestId,
                            mLastMutationStatus, "undo_history_reconciled");
                    return new RuleMutationResult(RuleServiceContract.RESULT_COMMITTED,
                            requestId, packageName, mRuleGeneration.get(), null, state,
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
                clearDiagnostic();
                mServiceConnection.logMutationTerminal(operation, packageName, requestId, reconciled,
                        "reconciled_committed");
                return localMutationResult(requestId, packageName, reconciled,
                        "committed; response reconciled from snapshot");
            }
            if (reconciled == RuleServiceContract.RESULT_REJECTED) {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        String.format(Locale.US, DiagnosticMessages.MUTATE_READBACK_NOT_FOUND_REQUEST_ID_DETAIL,
                                requestId)));
            } else {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.COMMIT_UNCERTAIN,
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
                mRuleGeneration.get(), null, null, message);
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
                    getToolbarHiddenItems(RuleServiceContract.GLOBAL_SCOPE), value);
        }
        ActRules rules = getRules(packageName);
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

    static boolean containsCommittedRule(ActRules rules, RuleRecord expected,
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

    static boolean containsSlot(ActRules rules, RuleRecord expected) {
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

    private PipeAsset openPipe(Bitmap bitmap) {
        if (bitmap == null) return null;
        if (bitmap.isRecycled()) {
            recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                    DiagnosticMessages.IMAGE_RECYCLED_MUTATION_CANCELLED_DETAIL));
            return null;
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            PipeAsset asset = new PipeAsset(pipe[0], pipe[1], bitmap);
            TaskExecutor.executeFdWrite(asset::write);
            return asset;
        } catch (IOException e) {
            recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                    String.format(Locale.US, DiagnosticMessages.IMAGE_PIPE_CREATE_FAILED_DETAIL, e.getMessage())));
            Logger.w(TAG, "create image pipe failed", e);
            return null;
        }
    }

    private static void awaitPipe(PipeAsset asset) {
        if (asset == null) return;
        try {
            if (!asset.finished.await(10, TimeUnit.SECONDS)) {
                asset.failure.compareAndSet(null,
                        new IOException("pipe writer did not stop within 10 seconds"));
                asset.closeWrite();
                Logger.w(TAG, "image pipe writer timed out");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asset.failure.compareAndSet(null, e);
            asset.closeWrite();
            Logger.w(TAG, "image pipe writer wait interrupted", e);
        }
    }

    private static void closePipe(PipeAsset asset) {
        if (asset == null) return;
        asset.closeRead();
        asset.closeWrite();
    }

    private static Throwable firstFailure(PipeAsset first, PipeAsset second) {
        Throwable failure = first == null ? null : first.failure.get();
        return failure != null || second == null ? failure : second.failure.get();
    }

    private final class PipeAsset {
        final ParcelFileDescriptor readEnd;
        private ParcelFileDescriptor writeEnd;
        final Bitmap bitmap;
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        PipeAsset(ParcelFileDescriptor readEnd, ParcelFileDescriptor writeEnd, Bitmap bitmap) {
            this.readEnd = readEnd;
            this.writeEnd = writeEnd;
            this.bitmap = bitmap;
        }

        void write() {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                writeEnd = null;
                if (!bitmap.compress(Bitmap.CompressFormat.WEBP, 80, output)) {
                    throw new IOException("bitmap encode failed");
                }
                output.flush();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                closeWrite();
            } finally {
                finished.countDown();
            }
        }

        void closeRead() {
            Closeables.closeQuietly(readEnd);
        }

        void closeWrite() {
            ParcelFileDescriptor current = writeEnd;
            writeEnd = null;
            Closeables.closeQuietly(current);
        }
    }

    public ParcelFileDescriptor openImageFileDescriptor(String path) {
        ServiceConnection.Connection c = ensureConnection(); if (c == null) return null;
        try { return c.service.openImageFileDescriptor(path); }
        catch (RemoteException e) { logError("openImageFileDescriptor", c, e); return null; }
    }

    public String getToolbarHiddenItems(String packageName) {
        ServiceConnection.Connection c = ensureConnection(); if (c == null) return null;
        try { return c.service.getToolbarHiddenItems(packageName); }
        catch (RemoteException e) { logError("getToolbarHiddenItems", c, e); return null; }
    }

    public boolean setToolbarHiddenItems(String items) {
        return mutate(RuleServiceContract.GLOBAL_SCOPE, RuleServiceContract.MUTATION_SET_TOOLBAR,
                null, null, null, items);
    }

    public void forwardLog(int level, String tag, String msg, long timestamp) {
        mLogBridge.forwardLog(level, tag, msg, timestamp);
    }
    public void forwardLog(String packageName, int level, String tag, String msg, long timestamp) {
        mLogBridge.forwardLog(packageName, level, tag, msg, timestamp);
    }

    public boolean beginRestore() {
        if (mRestoreLease != null) return true;
        mRestoreLease = openLease(RuleServiceContract.OP_RESTORE, null);
        return mRestoreLease != null;
    }
    public boolean beginBackup() {
        if (mBackupLease != null) return false;
        mBackupLease = openLease(RuleServiceContract.OP_BACKUP, null);
        return mBackupLease != null;
    }
    public void endRestore() {
        if (mRestoreLease != null && closeLease(mRestoreLease)) mRestoreLease = null;
    }
    public void endBackup() {
        if (mBackupLease != null && closeLease(mBackupLease)) mBackupLease = null;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    public interface ObserverCallback {
        void onEditModeChanged(boolean enabled, long editRevision, long connectionEpoch);
        void onRulesInvalidated(String packageName, long generation, long connectionEpoch);
    }

    private final class ObserverRelay extends IRuleObserver.Stub {
        private final long mEpoch;
        private final ObserverCallback mObserver;

        ObserverRelay(long epoch, ObserverCallback observer) {
            mEpoch = epoch;
            mObserver = observer;
        }

        @Override public void onEditModeChanged(boolean enable, long editRevision) {
            if (acceptEditState(mEpoch, enable, editRevision)) {
                try {
                    mObserver.onEditModeChanged(enable, editRevision, mEpoch);
                } catch (Throwable failure) {
                    Logger.w(TAG, "observer edit callback failed epoch=" + mEpoch, failure);
                }
            }
        }

        @Override public void onRulesInvalidated(String packageName, long generation) {
            if (acceptRuleGeneration(mEpoch, generation)) {
                try {
                    mObserver.onRulesInvalidated(packageName, generation, mEpoch);
                } catch (Throwable failure) {
                    Logger.w(TAG, "observer rules callback failed package=" + packageName
                            + " generation=" + generation + " epoch=" + mEpoch, failure);
                }
            }
        }
    }

    private static final class ObserverSubscription {
        final String packageName;
        final ObserverCallback observer;
        private volatile long remoteEpoch = -1L;
        private volatile IRuleObserver remote;

        ObserverSubscription(String packageName, ObserverCallback observer) {
            this.packageName = packageName; this.observer = observer;
        }

        void bind(long epoch, IRuleObserver relay) {
            remoteEpoch = epoch;
            remote = relay;
        }

        IRuleObserver remoteForEpoch(long epoch) {
            return remoteEpoch == epoch ? remote : null;
        }

        void clearRemote() {
            remoteEpoch = -1L;
            remote = null;
        }

        boolean matches(String packageName, ObserverCallback observer) {
            return this.packageName.equals(packageName) && this.observer == observer;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof ObserverSubscription)) return false;
            ObserverSubscription that = (ObserverSubscription) other;
            return matches(that.packageName, that.observer);
        }
        @Override public int hashCode() {
            return 31 * packageName.hashCode() + System.identityHashCode(observer);
        }
    }
}
