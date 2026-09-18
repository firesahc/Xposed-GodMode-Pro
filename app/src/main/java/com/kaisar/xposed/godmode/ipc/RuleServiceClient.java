package com.kaisar.xposed.godmode.ipc;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SharedMemory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.editor.RuleEditorClient;
import com.kaisar.xposed.godmode.ipc.contract.IRuleObserver;
import com.kaisar.xposed.godmode.ipc.contract.ObserverRegistrationParcel;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.RuleSnapshotParcel;
import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

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
    private final LeaseHub mLeaseHub;
    private final ImageStore mImageStore;
    private final RuleEditorClient mRuleEditor;

    private RuleServiceClient() {
        mLogBridge = new LogBridge(mServiceConnection);
        mLeaseHub = new LeaseHub(mServiceConnection);
        mImageStore = new ImageStore(mServiceConnection);
        // ClientEditState 是观察者链路的编辑投影，留在 Client 侧；Hub 经 Listener 回调
        // 通知投影清理，避免投影与租约机制耦合。
        mLeaseHub.setListener(new LeaseHub.Listener() {
            @Override public boolean isEditEnabled() { return mEditState.isEnabled(); }
            @Override public long editRevision() { return mEditState.revision(); }
            @Override public void onEditLeaseRevoked() { mEditState.reset(); }
            @Override public void onLeaseCloseBusy(String token) {
                if (token != null && token.equals(mEditState.leaseToken())) {
                    mEditState.markClosing(token);
                }
            }
        });
        mRuleEditor = new RuleEditorClient(mServiceConnection, mLeaseHub, mImageStore,
                new RuleEditorClient.Host() {
                    @Override public ActRules getRules(String packageName) {
                        return RuleServiceClient.this.getRules(packageName);
                    }
                    @Override public String getToolbarHiddenItems(String packageName) {
                        return RuleServiceClient.this.getToolbarHiddenItems(packageName);
                    }
                    @Override public long ruleGeneration() { return mRuleGeneration.get(); }
                    @Override public long editRevision() { return mEditState.revision(); }
                });
        mServiceConnection.setConnectionListener(new ServiceConnection.ConnectionListener() {
            @Override public void onConnectionCleared() {
                mLeaseHub.clearLeases();
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
                String token = mLeaseHub.openLease(RuleServiceContract.OP_EDIT, null);
                if (token == null) return false;
                mEditState.setLeaseToken(token);
            }
            return true;
        }
        String token = mEditState.leaseToken();
        if (token != null) {
            if (!mLeaseHub.closeLease(token)) return false;
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

    /** B3：写入 owner 已收归 {@link RuleEditorClient}，本类只保留公开签名转发。 */
    public RuleEditorClient getRuleEditor() {
        return mRuleEditor;
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot) {
        return mRuleEditor.writeRule(packageName, rule, snapshot);
    }

    public boolean writeRule(String packageName, RuleRecord rule, Bitmap snapshot, Bitmap modifiedSnapshot) {
        return mRuleEditor.writeRule(packageName, rule, snapshot, modifiedSnapshot);
    }

    public RuleMutationResult writeUndoableRule(String packageName, RuleRecord rule,
                                                Bitmap snapshot, Bitmap modifiedSnapshot) {
        return mRuleEditor.writeUndoableRule(packageName, rule, snapshot, modifiedSnapshot);
    }
    public boolean updateRule(String packageName, RuleRecord rule) {
        return mRuleEditor.updateRule(packageName, rule);
    }
    public boolean deleteRule(String packageName, RuleRecord rule) {
        return mRuleEditor.deleteRule(packageName, rule);
    }
    public boolean deleteRules(String packageName) {
        return mRuleEditor.deleteRules(packageName);
    }

    public int getLastMutationStatus() {
        return mRuleEditor.getLastMutationStatus();
    }

    public UndoStateParcel getUndoState(String packageName) {
        return mRuleEditor.getUndoState(packageName);
    }

    public UndoResultParcel undoLatest(String packageName, UndoStateParcel expected) {
        return mRuleEditor.undoLatest(packageName, expected);
    }

    /** 身份校验已收归 ServiceConnection；测试兼容保留包内转发。 */
    static boolean isExpectedIdentity(ServiceIdentityParcel identity) {
        return ServiceConnection.isExpectedIdentity(identity);
    }

    /** 对账谓词已收归 RuleEditorClient；测试兼容保留包内转发。 */
    static boolean containsCommittedRule(ActRules rules, RuleRecord expected,
                                         boolean hadMainImage, boolean hadModifiedImage) {
        return RuleEditorClient.containsCommittedRule(rules, expected,
                hadMainImage, hadModifiedImage);
    }

    /** 对账谓词已收归 RuleEditorClient；测试兼容保留包内转发。 */
    static boolean containsSlot(ActRules rules, RuleRecord expected) {
        return RuleEditorClient.containsSlot(rules, expected);
    }

    /** B4：图片库已收归 {@link ImageStore}，本类只保留 FD 公开签名转发。 */
    public ImageStore getImageStore() {
        return mImageStore;
    }

    public ParcelFileDescriptor openImageFileDescriptor(String path) {
        return mImageStore.openImageFileDescriptor(path);
    }

    public String getToolbarHiddenItems(String packageName) {
        ServiceConnection.Connection c = ensureConnection(); if (c == null) return null;
        try { return c.service.getToolbarHiddenItems(packageName); }
        catch (RemoteException e) { logError("getToolbarHiddenItems", c, e); return null; }
    }

    public boolean setToolbarHiddenItems(String items) {
        return mRuleEditor.setToolbarHiddenItems(items);
    }

    public void forwardLog(int level, String tag, String msg, long timestamp) {
        mLogBridge.forwardLog(level, tag, msg, timestamp);
    }
    public void forwardLog(String packageName, int level, String tag, String msg, long timestamp) {
        mLogBridge.forwardLog(packageName, level, tag, msg, timestamp);
    }

    public boolean beginRestore() {
        return mLeaseHub.beginRestore();
    }
    public boolean beginBackup() {
        return mLeaseHub.beginBackup();
    }
    public void endRestore() {
        mLeaseHub.endRestore();
    }
    public void endBackup() {
        mLeaseHub.endBackup();
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
