package com.kaisar.xposed.godmode.ipc;

import android.graphics.Bitmap;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SharedMemory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.contract.ILeaseOwner;
import com.kaisar.xposed.godmode.ipc.contract.IRuleObserver;
import com.kaisar.xposed.godmode.ipc.contract.IRuleService;
import com.kaisar.xposed.godmode.ipc.contract.ObserverRegistrationParcel;
import com.kaisar.xposed.godmode.ipc.contract.OperationLeaseParcel;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationRequest;
import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.RuleSnapshotParcel;
import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.util.TaskExecutor;
import com.kaisar.xservicemanager.XServiceManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
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
    private static final int CONNECT_RETRY_COUNT = 3;
    private static final long[] CONNECT_RETRY_DELAYS_MS = {80L, 160L};
    private static final int MAX_PENDING_LOGS = 512;
    private static volatile RuleServiceClient instance;

    private final Gson mGson = new GsonBuilder().create();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> mBinderDeathListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ObserverSubscription> mObserverSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong mConnectionEpoch = new AtomicLong();
    private final AtomicLong mRuleGeneration = new AtomicLong();
    private final ClientEditState mEditState = new ClientEditState();
    private final Object mLogLock = new Object();
    private final ArrayDeque<PendingLog> mPendingLogs = new ArrayDeque<>(MAX_PENDING_LOGS);
    private long mDroppedPendingLogs;
    private long mRejectedPendingLogs;
    private final ILeaseOwner mLeaseOwner = new ILeaseOwner.Stub() {
        @Override public void onLeaseRevoked(int reason) {
            boolean hadRestoreLease = mRestoreLease != null;
            boolean hadBackupLease = mBackupLease != null;
            boolean wasEditEnabled = mEditState.isEnabled();
            long editRevision = mEditState.revision();
            long epoch = mConnectionEpoch.get();
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
    private volatile Connection mConnection;
    private volatile String mLastError;
    private volatile ServiceDiagnostic mServiceDiagnostic;
    private volatile int mServiceState = RuleServiceContract.STARTING;
    private volatile String mRestoreLease;
    private volatile String mBackupLease;
    private volatile int mLastMutationStatus = RuleServiceContract.RESULT_NO_CHANGE;

    private RuleServiceClient() { }

    /** Installs the process-side durable sink for Logger and XServiceManager diagnostics. */
    public void installProcessLogging(String packageName) {
        final String sourcePackage = packageName == null ? "unknown" : packageName;
        Logger.setWriter((level, tag, msg, timestamp) ->
                forwardLog(sourcePackage, level, tag, msg, timestamp));
        XServiceManager.setLogDelegate(new XServiceManager.LogDelegate() {
            @Override public void d(String tag, String msg) { Logger.d(tag, msg); }
            @Override public void i(String tag, String msg) { Logger.i(tag, msg); }
            @Override public void w(String tag, String msg) { Logger.w(tag, msg); }
            @Override public void w(String tag, String msg, Throwable tr) {
                Logger.w(tag, msg, tr);
            }
            @Override public void e(String tag, String msg) { Logger.e(tag, msg); }
            @Override public void e(String tag, String msg, Throwable tr) {
                Logger.e(tag, msg, tr);
            }
        });
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

    private Connection ensureConnection() {
        Connection current = mConnection;
        if (isReady(current)) return flushReadyPendingLogs(current);
        if (mServiceState == RuleServiceContract.REBOOT_REQUIRED) return null;
        synchronized (this) {
            current = mConnection;
            if (isReady(current)) return flushReadyPendingLogs(current);
            IBinder remote = connectWithRetry();
            if (remote == null) {
                mServiceState = RuleServiceContract.FAILED;
                recordDiagnostic(buildBridgeDiagnostic());
                return null;
            }
            try {
                String descriptor = remote.getInterfaceDescriptor();
                if (!RuleServiceContract.DESCRIPTOR.equals(descriptor)) {
                    markRebootRequired(ServiceDiagnostic.of(
                            ServiceDiagnostic.Type.DESCRIPTOR_MISMATCH,
                            "规则服务 descriptor 不匹配: " + descriptor));
                    return null;
                }
                IRuleService service = IRuleService.Stub.asInterface(remote);
                ServiceIdentityParcel identity = service.getServiceIdentity();
                if (!isExpectedIdentity(identity)) {
                    markRebootRequired(ServiceDiagnostic.of(
                            ServiceDiagnostic.Type.CONTRACT_MISMATCH,
                            "规则服务身份或合同指纹不匹配"));
                    return null;
                }
                int state = identity.serviceState;
                if (state != RuleServiceContract.READY) {
                    mServiceState = state;
                    recordDiagnostic(ServiceDiagnostic.forServiceState(state,
                            "规则服务尚未就绪，state=" + state));
                    return null;
                }
                final Connection connection = new Connection(remote, service, mConnectionEpoch.incrementAndGet());
                remote.linkToDeath(() -> onBinderDied(connection), 0);
                mConnection = connection;
                mServiceState = RuleServiceContract.READY;
                clearDiagnostic();
                RemoteException pendingLogFailure = flushPendingLogs(connection);
                if (pendingLogFailure != null) {
                    logError("flushLogs", connection, pendingLogFailure);
                    if (!isReady(connection)) return null;
                }
                reregisterObservers(connection);
                return connection;
            } catch (RemoteException e) {
                if (remote.isBinderAlive()) {
                    mServiceState = RuleServiceContract.FAILED;
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            "规则服务握手失败: " + e.getMessage()));
                } else {
                    mServiceState = RuleServiceContract.STARTING;
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.BINDER_DIED,
                            "规则服务握手期间 Binder 已死亡: " + e.getMessage()));
                }
                Logger.e(TAG, "rule service handshake failed state=" + mServiceState, e);
                return null;
            } catch (RuntimeException e) {
                mServiceState = RuleServiceContract.FAILED;
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        "规则服务握手异常: " + e.getMessage()));
                Logger.e(TAG, "rule service handshake exception state=" + mServiceState, e);
                return null;
            }
        }
    }

    private void onBinderDied(Connection dead) {
        boolean notify = false;
        synchronized (this) {
            if (mConnection != dead) return;
            clearConnectionStateLocked(RuleServiceContract.STARTING,
                    ServiceDiagnostic.of(ServiceDiagnostic.Type.BINDER_DIED,
                            "规则服务 Binder 已死亡，等待重新连接"));
            notify = true;
        }
        if (notify) {
            Logger.w(TAG, "rule service binder died epoch=" + dead.epoch);
            notifyBinderDead();
        }
    }

    private void markRebootRequired(ServiceDiagnostic diagnostic) {
        synchronized (this) {
            clearConnectionStateLocked(RuleServiceContract.REBOOT_REQUIRED, diagnostic);
        }
        Logger.e(TAG, diagnostic.getTechnicalDetail());
    }

    private void clearConnectionStateLocked(int state, ServiceDiagnostic diagnostic) {
        mConnection = null;
        mConnectionEpoch.incrementAndGet();
        mServiceState = state;
        recordDiagnostic(diagnostic);
        mRestoreLease = null;
        mBackupLease = null;
        mEditState.reset();
        mRuleGeneration.set(0L);
        for (ObserverSubscription subscription : mObserverSubscriptions) {
            subscription.clearRemote();
        }
    }

    private boolean isReady(Connection connection) {
        return connection != null && connection.binder.isBinderAlive()
                && mServiceState == RuleServiceContract.READY;
    }

    private IBinder connectWithRetry() {
        for (int i = 0; i < CONNECT_RETRY_COUNT; i++) {
            if (XServiceManager.pingBridge()) {
                IBinder service = XServiceManager.getService(RuleServiceContract.SERVICE_NAME);
                if (service != null) return service;
            }
            recordDiagnostic(buildBridgeDiagnostic());
            if (i < CONNECT_RETRY_DELAYS_MS.length) sleepQuietly(CONNECT_RETRY_DELAYS_MS[i]);
        }
        return null;
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static ServiceDiagnostic buildBridgeDiagnostic() {
        String error = XServiceManager.getLastError();
        XServiceManager.BridgeStatus status = XServiceManager.getRemoteBridgeStatus();
        String detail;
        if (status != null && !status.bridgeInstalled) {
            detail = "XServiceManager 桥接未安装";
            return ServiceDiagnostic.of(ServiceDiagnostic.Type.BRIDGE_UNAVAILABLE, detail);
        } else if (status != null && !status.systemServer) {
            detail = "XServiceManager 未运行在 system_server，注入失败";
            return ServiceDiagnostic.of(ServiceDiagnostic.Type.BRIDGE_UNAVAILABLE, detail);
        }
        detail = error == null || error.trim().isEmpty()
                ? "桥接已就绪，规则服务尚未注册" : error;
        return status == null
                ? ServiceDiagnostic.of(ServiceDiagnostic.Type.BRIDGE_UNAVAILABLE, detail)
                : ServiceDiagnostic.of(ServiceDiagnostic.Type.SERVICE_STARTING, detail);
    }

    public String getLastError() { return mLastError; }
    public ServiceDiagnostic getServiceDiagnostic() { return mServiceDiagnostic; }
    public String getServiceFailureMessage() {
        ServiceDiagnostic diagnostic = mServiceDiagnostic;
        return diagnostic == null ? null : diagnostic.getUserMessage();
    }

    private void recordDiagnostic(ServiceDiagnostic diagnostic) {
        mServiceDiagnostic = diagnostic;
        if (diagnostic == null) {
            mLastError = null;
            return;
        }
        String detail = diagnostic.getTechnicalDetail();
        mLastError = detail == null || detail.trim().isEmpty()
                ? diagnostic.getSummary() : detail;
    }

    private void clearDiagnostic() {
        recordDiagnostic(null);
    }

    private void recordResultFailure(int status, String detail) {
        recordDiagnostic(ServiceDiagnostic.forResultStatus(status, detail));
    }
    public int getServiceState() { ensureConnection(); return mServiceState; }
    public boolean isReady() { return ensureConnection() != null; }
    public boolean isConnected() { return isReady(); }
    public boolean hasReadyConnection() { return isReady(mConnection); }
    public boolean awaitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        do {
            if (isReady()) return true;
            if (mServiceState == RuleServiceContract.REBOOT_REQUIRED) return false;
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) return false;
            sleepQuietly(Math.min(100L, remaining));
        } while (true);
    }

    public void addBinderDeathListener(Runnable listener) {
        if (listener != null && !mBinderDeathListeners.contains(listener)) mBinderDeathListeners.add(listener);
    }
    public void removeBinderDeathListener(Runnable listener) {
        if (listener != null) mBinderDeathListeners.remove(listener);
    }
    private void notifyBinderDead() {
        for (Runnable listener : mBinderDeathListeners) {
            mMainHandler.post(() -> {
                try { listener.run(); }
                catch (Throwable t) { Logger.w(TAG, "binder death listener failed", t); }
            });
        }
    }

    private Connection flushReadyPendingLogs(Connection connection) {
        synchronized (mLogLock) {
            if (mPendingLogs.isEmpty()) return connection;
        }
        RemoteException failure = flushPendingLogs(connection);
        if (failure != null) {
            logError("flushLogs", connection, failure);
            return isReady(connection) ? connection : null;
        }
        return connection;
    }

    private void reregisterObservers(Connection connection) {
        for (ObserverSubscription subscription : mObserverSubscriptions) {
            try { registerObserver(connection, subscription); }
            catch (RemoteException e) { Logger.w(TAG, "observer re-register failed", e); }
        }
    }

    private void registerObserver(Connection connection, ObserverSubscription subscription)
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

    static boolean isExpectedIdentity(ServiceIdentityParcel identity) {
        return identity != null
                && identity.protocolVersion == RuleServiceContract.PROTOCOL_VERSION
                && identity.buildVersionCode == RuleServiceContract.BUILD_VERSION_CODE
                && RuleServiceContract.CONTRACT_FINGERPRINT.equals(identity.contractFingerprint);
    }

    public boolean hasLight() {
        Connection c = ensureConnection(); if (c == null) return false;
        try { return c.service.hasLight(); } catch (RemoteException e) { logError("hasLight", c, e); return false; }
    }

    public synchronized boolean setEditMode(boolean enable) {
        if (enable) {
            if (!mEditState.canRequestEnable()) {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.OPERATION_BUSY,
                        "编辑正在关闭，请等待当前提交完成"));
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
        Connection c = ensureConnection(); if (c == null) return null;
        try {
            OperationLeaseParcel lease = c.service.openOperation(type, packageName, mLeaseOwner);
            if (lease != null && lease.status == RuleServiceContract.RESULT_COMMITTED) {
                clearDiagnostic();
                return lease.token;
            }
            if (lease == null) {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        "规则服务未返回操作租约"));
            } else {
                recordResultFailure(lease.status, lease.message);
            }
            return null;
        } catch (RemoteException e) { logError("openOperation", c, e); return null; }
    }

    private boolean closeLease(String token) {
        Connection c = ensureConnection(); if (c == null) return false;
        try {
            OperationLeaseParcel result = c.service.closeOperation(token, mLeaseOwner);
            boolean closed = result != null
                    && (result.status == RuleServiceContract.RESULT_COMMITTED
                    || result.status == RuleServiceContract.RESULT_NO_CHANGE);
            if (closed) clearDiagnostic();
            if (!closed) {
                if (result == null) {
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            "规则服务未返回关闭结果"));
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
        Connection c = ensureConnection();
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
        Connection c = ensureConnection();
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
        synchronized (this) {
            return isCurrentEpochLocked(epoch) && mEditState.accept(enabled, revision);
        }
    }

    private boolean acceptRuleGeneration(long epoch, long generation) {
        if (!isCurrentEpoch(epoch)) return false;
        return generation >= mRuleGeneration.get() && isCurrentEpoch(epoch);
    }

    public boolean isCurrentEditEvent(long epoch, long revision) {
        return ClientEventOrder.isCurrent(epoch, mConnectionEpoch.get(),
                revision, mEditState.revision()) && isCurrentEpoch(epoch);
    }

    public boolean isCurrentRuleEvent(long epoch, long generation) {
        return ClientEventOrder.isCurrent(epoch, mConnectionEpoch.get(),
                generation, mRuleGeneration.get()) && isCurrentEpoch(epoch);
    }

    private boolean isCurrentEpoch(long epoch) {
        synchronized (this) { return isCurrentEpochLocked(epoch); }
    }

    private boolean isCurrentEpochLocked(long epoch) {
        return mConnection != null && mConnection.epoch == epoch
                && mConnectionEpoch.get() == epoch && isReady(mConnection);
    }

    public AppRules getAllRules() {
        return getAllRulesAtLeast(0L);
    }

    public AppRules getAllRulesAtLeast(long minimumGeneration) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Connection c = ensureConnection(); if (c == null) return null;
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
                logError("getAllRules", c, asRemote(e)); return null;
            }
        }
        Logger.w(TAG, "getAllRules could not satisfy minimum generation=" + minimumGeneration);
        return null;
    }

    public ActRules getRules(String packageName) { return getRulesAtLeast(packageName, 0L); }

    public ActRules getRulesAtLeast(String packageName, long minimumGeneration) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Connection c = ensureConnection(); if (c == null) return null;
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
                logError("getRules", c, asRemote(e));
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
            throw new IllegalStateException("无法读取规则快照", e);
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

    private boolean mutate(String packageName, int operation, RuleRecord rule, Bitmap main,
                           Bitmap modified, String value) {
        String requestId = UUID.randomUUID().toString();
        boolean temporary = false;
        String lease = mRestoreLease;
        if (lease == null) {
            lease = openLease(RuleServiceContract.OP_MUTATION, packageName);
            temporary = true;
        }
        if (lease == null) {
            mLastMutationStatus = RuleServiceContract.RESULT_BUSY;
            logMutationTerminal(operation, packageName, requestId,
                    mLastMutationStatus, "lease_unavailable");
            return false;
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
            logMutationTerminal(operation, packageName, requestId,
                    mLastMutationStatus, "image_pipe_unavailable");
            return false;
        }
        RuleMutationRequest request = new RuleMutationRequest(operation, requestId, lease,
                packageName, rule == null ? null : mGson.toJson(rule),
                mainPipe == null ? null : mainPipe.readEnd,
                modifiedPipe == null ? null : modifiedPipe.readEnd, value);
        Connection c = ensureConnection();
        boolean accepted = false;
        boolean uncertain = false;
        try {
            if (c != null) {
                RuleMutationResult result = c.service.mutate(request, mLeaseOwner);
                mLastMutationStatus = result == null ? RuleServiceContract.RESULT_UNCERTAIN
                        : result.status;
                uncertain = result == null;
                accepted = result != null && (result.status == RuleServiceContract.RESULT_COMMITTED
                        || result.status == RuleServiceContract.RESULT_NO_CHANGE);
                if (accepted) clearDiagnostic();
                if (!accepted) {
                    String mutationError = result == null ? "规则服务未返回提交结果" : result.message;
                    if (result == null) {
                        recordResultFailure(RuleServiceContract.RESULT_UNCERTAIN, mutationError);
                    } else {
                        recordResultFailure(result.status, mutationError);
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
                    "规则提交结果未知，请刷新规则后再操作 (requestId=" + requestId + ")"));
        }
        finally {
            if (mainPipe != null) mainPipe.closeRead();
            if (modifiedPipe != null) modifiedPipe.closeRead();
            awaitPipe(mainPipe);
            awaitPipe(modifiedPipe);
            Throwable pipeFailure = firstFailure(mainPipe, modifiedPipe);
            if (pipeFailure != null) {
                Logger.w(TAG, "mutation image pipe failed operation="
                        + mutationOperationName(operation) + " package=" + packageName
                        + " requestId=" + requestId, pipeFailure);
                if (!accepted && !uncertain) {
                    mLastMutationStatus = RuleServiceContract.RESULT_WRITE_FAILED;
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            "图片管道写入失败: " + pipeFailure.getMessage()));
                }
            }
            closePipe(mainPipe);
            closePipe(modifiedPipe);
            ServiceDiagnostic mutationDiagnostic = mServiceDiagnostic;
            String mutationError = mLastError;
            if (temporary) closeLease(lease);
            if (!accepted && mutationDiagnostic != null) {
                mServiceDiagnostic = mutationDiagnostic;
                mLastError = mutationError;
            }
        }
        if (uncertain) {
            int reconciled = reconcileUncertain(packageName, operation, rule,
                    main != null, modified != null, value);
            mLastMutationStatus = reconciled;
            if (reconciled == RuleServiceContract.RESULT_COMMITTED) {
                clearDiagnostic();
                logMutationTerminal(operation, packageName, requestId, reconciled,
                        "reconciled_committed");
                return true;
            }
            if (reconciled == RuleServiceContract.RESULT_REJECTED) {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        "规则服务读回未发现本次提交，请刷新规则后再操作"
                                + " (requestId=" + requestId + ")"));
            } else {
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.COMMIT_UNCERTAIN,
                        "规则提交状态未知，请刷新规则后再操作"
                                + " (requestId=" + requestId + ")"));
            }
            logMutationTerminal(operation, packageName, requestId, reconciled,
                    "reconciled_inconclusive");
            return false;
        }
        logMutationTerminal(operation, packageName, requestId, mLastMutationStatus,
                accepted ? "accepted" : "rejected");
        return accepted;
    }

    private void logMutationTerminal(int operation, String packageName, String requestId,
                                     int status, String outcome) {
        String line = "mutation client complete operation=" + mutationOperationName(operation)
                + " requestId=" + requestId + " package=" + packageName
                + " status=" + mutationStatusName(status) + " outcome=" + outcome;
        if (status == RuleServiceContract.RESULT_COMMITTED
                || status == RuleServiceContract.RESULT_NO_CHANGE) {
            Logger.i(TAG, line);
        } else if (status == RuleServiceContract.RESULT_WRITE_FAILED
                || status == RuleServiceContract.RESULT_UNCERTAIN) {
            Logger.w(TAG, line);
        } else {
            Logger.d(TAG, line);
        }
    }

    private static String mutationOperationName(int operation) {
        switch (operation) {
            case RuleServiceContract.MUTATION_WRITE: return "write";
            case RuleServiceContract.MUTATION_UPDATE: return "update";
            case RuleServiceContract.MUTATION_DELETE: return "delete";
            case RuleServiceContract.MUTATION_DELETE_ALL: return "delete_all";
            case RuleServiceContract.MUTATION_SET_TOOLBAR: return "set_toolbar";
            default: return "unknown(" + operation + ")";
        }
    }

    private static String mutationStatusName(int status) {
        switch (status) {
            case RuleServiceContract.RESULT_COMMITTED: return "committed";
            case RuleServiceContract.RESULT_NO_CHANGE: return "no_change";
            case RuleServiceContract.RESULT_BUSY: return "busy";
            case RuleServiceContract.RESULT_REJECTED: return "rejected";
            case RuleServiceContract.RESULT_WRITE_FAILED: return "write_failed";
            case RuleServiceContract.RESULT_REBOOT_REQUIRED: return "reboot_required";
            case RuleServiceContract.RESULT_INVALID: return "invalid";
            case RuleServiceContract.RESULT_UNCERTAIN: return "uncertain";
            default: return "unknown(" + status + ")";
        }
    }

    private int reconcileUncertain(String packageName, int operation, RuleRecord rule,
                                   boolean hadMainImage, boolean hadModifiedImage,
                                   String value) {
        if (operation == RuleServiceContract.MUTATION_SET_TOOLBAR) {
            String current = getToolbarHiddenItems(RuleServiceContract.GLOBAL_SCOPE);
            if (current == null) return RuleServiceContract.RESULT_UNCERTAIN;
            return current.equals(value == null ? "" : value)
                    ? RuleServiceContract.RESULT_COMMITTED
                    : RuleServiceContract.RESULT_REJECTED;
        }
        ActRules rules = getRules(packageName);
        if (rules == null) return RuleServiceContract.RESULT_UNCERTAIN;
        switch (operation) {
            case RuleServiceContract.MUTATION_WRITE:
            case RuleServiceContract.MUTATION_UPDATE:
                return containsCommittedRule(rules, rule, hadMainImage, hadModifiedImage)
                        ? RuleServiceContract.RESULT_COMMITTED
                        : RuleServiceContract.RESULT_REJECTED;
            case RuleServiceContract.MUTATION_DELETE:
                return containsSlot(rules, rule) ? RuleServiceContract.RESULT_REJECTED
                        : RuleServiceContract.RESULT_COMMITTED;
            case RuleServiceContract.MUTATION_DELETE_ALL:
                return isEmpty(rules) ? RuleServiceContract.RESULT_COMMITTED
                        : RuleServiceContract.RESULT_REJECTED;
            default:
                return RuleServiceContract.RESULT_UNCERTAIN;
        }
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
                if (actual.contentEquals(normalized)) return true;
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
                    "图片已回收，取消规则提交"));
            return null;
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            PipeAsset asset = new PipeAsset(pipe[0], pipe[1], bitmap);
            TaskExecutor.executeFdWrite(asset::write);
            return asset;
        } catch (IOException e) {
            recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                    "创建图片管道失败: " + e.getMessage()));
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
            try { if (readEnd != null) readEnd.close(); } catch (IOException ignored) { }
        }

        void closeWrite() {
            ParcelFileDescriptor current = writeEnd;
            writeEnd = null;
            try { if (current != null) current.close(); } catch (IOException ignored) { }
        }
    }

    public ParcelFileDescriptor openImageFileDescriptor(String path) {
        Connection c = ensureConnection(); if (c == null) return null;
        try { return c.service.openImageFileDescriptor(path); }
        catch (RemoteException e) { logError("openImageFileDescriptor", c, e); return null; }
    }

    public String getToolbarHiddenItems(String packageName) {
        Connection c = ensureConnection(); if (c == null) return null;
        try { return c.service.getToolbarHiddenItems(packageName); }
        catch (RemoteException e) { logError("getToolbarHiddenItems", c, e); return null; }
    }

    public boolean setToolbarHiddenItems(String items) {
        return mutate(RuleServiceContract.GLOBAL_SCOPE, RuleServiceContract.MUTATION_SET_TOOLBAR,
                null, null, null, items);
    }

    public void forwardLog(int level, String tag, String msg, long timestamp) {
        forwardLog("unknown", level, tag, msg, timestamp);
    }
    public void forwardLog(String packageName, int level, String tag, String msg, long timestamp) {
        // Logging never establishes Binder synchronously. While Binder is unavailable, retain a
        // bounded process-local backlog so handshake/death-window diagnostics can be flushed by
        // the next successful connection instead of disappearing silently.
        PendingLog pending = new PendingLog(packageName == null ? "unknown" : packageName,
                level, tag, msg, timestamp);
        Connection c = mConnection;
        RemoteException failure = null;
        synchronized (mLogLock) {
            if (!isReady(c) || !mPendingLogs.isEmpty()) {
                enqueuePendingLogLocked(pending, false);
                return;
            }
            try {
                sendLog(c, pending);
            } catch (RemoteException e) {
                enqueuePendingLogLocked(pending, true);
                failure = e;
            }
        }
        if (failure != null) logError("log", c, failure);
    }

    private void enqueuePendingLogLocked(PendingLog pending, boolean first) {
        if (mPendingLogs.size() >= MAX_PENDING_LOGS) {
            mPendingLogs.removeFirst();
            mDroppedPendingLogs++;
        }
        if (first) {
            mPendingLogs.addFirst(pending);
        } else {
            mPendingLogs.addLast(pending);
        }
    }

    private RemoteException flushPendingLogs(Connection connection) {
        synchronized (mLogLock) {
            while (!mPendingLogs.isEmpty()) {
                PendingLog pending = mPendingLogs.peekFirst();
                try {
                    sendLog(connection, pending);
                    mPendingLogs.removeFirst();
                } catch (RemoteException e) {
                    if (connection.binder.isBinderAlive()) {
                        // A live Binder with a rejected log (for example an invalid package
                        // identity) must not block every later record in the backlog.
                        mPendingLogs.removeFirst();
                        mRejectedPendingLogs++;
                        continue;
                    }
                    return e;
                }
            }
            long dropped = mDroppedPendingLogs;
            long rejected = mRejectedPendingLogs;
            mDroppedPendingLogs = 0L;
            mRejectedPendingLogs = 0L;
            if (dropped > 0L || rejected > 0L) {
                Logger.w(TAG, "pending logs not persisted dropped=" + dropped
                        + " rejected=" + rejected);
            }
            return null;
        }
    }

    private static void sendLog(Connection connection, PendingLog pending) throws RemoteException {
        connection.service.log(pending.level, pending.packageName, pending.timestamp,
                pending.tag, pending.message);
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

    private void logError(String method, Connection connection, RemoteException e) {
        boolean notify = false;
        boolean current = false;
        String event = "RuleServiceClient#" + method + " call failed";
        String detail = event + ": " + e.getMessage();
        synchronized (this) {
            current = mConnection == connection;
            if (current && (e instanceof DeadObjectException || !connection.binder.isBinderAlive())) {
                clearConnectionStateLocked(RuleServiceContract.STARTING,
                        ServiceDiagnostic.of(ServiceDiagnostic.Type.BINDER_DIED, detail));
                notify = true;
            }
        }
        if (notify) notifyBinderDead();
        if (current && !notify) {
            recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN, detail));
        }
        Logger.e(TAG, event, e);
    }

    private static RemoteException asRemote(Exception e) {
        RemoteException remote = new RemoteException(e.getMessage());
        remote.initCause(e);
        return remote;
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    private static final class Connection {
        final IBinder binder;
        final IRuleService service;
        final long epoch;
        Connection(IBinder binder, IRuleService service, long epoch) {
            this.binder = binder; this.service = service; this.epoch = epoch;
        }
    }

    private static final class PendingLog {
        final String packageName;
        final int level;
        final String tag;
        final String message;
        final long timestamp;

        PendingLog(String packageName, int level, String tag, String message, long timestamp) {
            this.packageName = packageName;
            this.level = level;
            this.tag = tag;
            this.message = message;
            this.timestamp = timestamp;
        }
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
