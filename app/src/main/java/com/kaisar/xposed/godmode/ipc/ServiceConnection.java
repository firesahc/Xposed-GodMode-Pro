package com.kaisar.xposed.godmode.ipc;

import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.contract.IRuleService;
import com.kaisar.xposed.godmode.ipc.contract.ServiceIdentityParcel;
import com.kaisar.xservicemanager.XServiceManager;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B1 连接核：持有 RuleService Binder 连接全状态与建连/死亡/诊断语义。
 *
 * <p>行为零差抽取自 RuleServiceClient（ensure/onBinderDied/clear/isReady/
 * connectWithRetry/sleepQuietly/buildBridgeDiagnostic/notify/record/clearDiagnostic/
 * recordResultFailure/logError/asRemote/awaitReady/death监听/getter/hasLight/
 * mutation终端日志命名）。同步语义逐行保留：连接态单锁（本实例），
 * epoch CAS，三态（空连接/死亡/REBOOT）不变，只记日志不抛宿主。
 *
 * <p>协作口（本期 LogBridge/观察者/租约不动，核内保留转发点）：
 * <ul>
 *   <li>{@link ConnectionListener#onConnectionCleared()} —— Client 清理租约
 *       （mRestoreLease/mBackupLease）、mEditState、mRuleGeneration、observer.remote。
 *       核只管连接态，不管租约语义。</li>
 *   <li>{@link ConnectionListener#onConnectionEstablished(Connection)} —— Client 重注册观察者。</li>
 *   <li>{@link LogBridge#flushReadyPendingLogs(Connection)} /
 *       {@link LogBridge#flushPendingLogs(Connection)} —— Client 侧 LogBridge
 *        backlog 冲刷实现；未设置时分别退化为直接返回连接 / 跳过冲刷。</li>
 * </ul>
 * 回调均在连接锁内触发（与原 synchronized(this) 粗粒度一致，含 Binder IPC），
 * 实现必须非阻塞、不得再对 RuleServiceClient 加锁，可重入本核读方法。
 */
public final class ServiceConnection {
    private static final String TAG = "RuleServiceClient";
    private static final int CONNECT_RETRY_COUNT = 3;
    private static final long[] CONNECT_RETRY_DELAYS_MS = {80L, 160L};

    /** 连接句柄：binder + 服务代理 + 建连 epoch。 */
    public static final class Connection {
        public final IBinder binder;
        public final IRuleService service;
        public final long epoch;

        public Connection(IBinder binder, IRuleService service, long epoch) {
            this.binder = binder;
            this.service = service;
            this.epoch = epoch;
        }
    }

    /** 连接态清空 / 建连成功回调，由 Client 实现租约·订阅清理与观察者重注册。 */
    public interface ConnectionListener {
        void onConnectionCleared();
        void onConnectionEstablished(Connection connection);
    }

    /** LogBridge 协作口：本期 LogBridge 不动，核内保留转发点。 */
    public interface LogBridge {
        Connection flushReadyPendingLogs(Connection connection);
        RemoteException flushPendingLogs(Connection connection);
    }

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> mBinderDeathListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong mConnectionEpoch = new AtomicLong();

    private volatile Connection mConnection;
    private volatile String mLastError;
    private volatile ServiceDiagnostic mServiceDiagnostic;
    private volatile int mServiceState = RuleServiceContract.STARTING;
    private volatile ConnectionListener mConnectionListener;
    private volatile LogBridge mLogBridge;

    public void setConnectionListener(ConnectionListener listener) {
        mConnectionListener = listener;
    }

    public void setLogBridge(LogBridge bridge) {
        mLogBridge = bridge;
    }

    public Connection ensureConnection() {
        Connection current = mConnection;
        if (isReady(current)) return flushReadyViaBridge(current);
        if (mServiceState == RuleServiceContract.REBOOT_REQUIRED) return null;
        synchronized (this) {
            current = mConnection;
            if (isReady(current)) return flushReadyViaBridge(current);
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
                            String.format(Locale.US, DiagnosticMessages.DESCRIPTOR_MISMATCH_DETAIL, descriptor)));
                    return null;
                }
                IRuleService service = IRuleService.Stub.asInterface(remote);
                ServiceIdentityParcel identity = service.getServiceIdentity();
                if (!isExpectedIdentity(identity)) {
                    markRebootRequired(ServiceDiagnostic.of(
                            ServiceDiagnostic.Type.CONTRACT_MISMATCH,
                            DiagnosticMessages.IDENTITY_FINGERPRINT_MISMATCH_DETAIL));
                    return null;
                }
                int state = identity.serviceState;
                if (state != RuleServiceContract.READY) {
                    mServiceState = state;
                    recordDiagnostic(ServiceDiagnostic.forServiceState(state,
                            String.format(Locale.US, DiagnosticMessages.SERVICE_NOT_READY_STATE_DETAIL, state)));
                    return null;
                }
                final Connection connection = new Connection(remote, service, mConnectionEpoch.incrementAndGet());
                remote.linkToDeath(() -> onBinderDied(connection), 0);
                mConnection = connection;
                mServiceState = RuleServiceContract.READY;
                clearDiagnostic();
                RemoteException pendingLogFailure = flushViaBridge(connection);
                if (pendingLogFailure != null) {
                    logError("flushLogs", connection, pendingLogFailure);
                    if (!isReady(connection)) return null;
                }
                ConnectionListener listener = mConnectionListener;
                if (listener != null) listener.onConnectionEstablished(connection);
                return connection;
            } catch (RemoteException e) {
                if (remote.isBinderAlive()) {
                    mServiceState = RuleServiceContract.FAILED;
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            String.format(Locale.US, DiagnosticMessages.HANDSHAKE_FAILED_DETAIL, e.getMessage())));
                } else {
                    mServiceState = RuleServiceContract.STARTING;
                    recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.BINDER_DIED,
                            String.format(Locale.US, DiagnosticMessages.HANDSHAKE_BINDER_DEAD_DETAIL, e.getMessage())));
                }
                Logger.e(TAG, "rule service handshake failed state=" + mServiceState, e);
                return null;
            } catch (RuntimeException e) {
                mServiceState = RuleServiceContract.FAILED;
                recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        String.format(Locale.US, DiagnosticMessages.HANDSHAKE_UNEXPECTED_FAILURE_DETAIL, e.getMessage())));
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
                            DiagnosticMessages.BINDER_DIED_AWAITING_RECONNECT_DETAIL));
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

    /** 只清连接态（连接/epoch/服务态/诊断），租约·订阅由 listener 清。 */
    private void clearConnectionStateLocked(int state, ServiceDiagnostic diagnostic) {
        mConnection = null;
        mConnectionEpoch.incrementAndGet();
        mServiceState = state;
        recordDiagnostic(diagnostic);
        ConnectionListener listener = mConnectionListener;
        if (listener != null) listener.onConnectionCleared();
    }

    public boolean isReady(Connection connection) {
        return connection != null && connection.binder.isBinderAlive()
                && mServiceState == RuleServiceContract.READY;
    }

    /** 当前 epoch 下连接仍为最新且就绪（原 isCurrentEpochLocked 语义）。 */
    public boolean isCurrentEpoch(long epoch) {
        synchronized (this) {
            return mConnection != null && mConnection.epoch == epoch
                    && mConnectionEpoch.get() == epoch && isReady(mConnection);
        }
    }

    public long getConnectionEpoch() {
        return mConnectionEpoch.get();
    }

    /** 非建连式快照读，供 forwardLog 等调用方使用。 */
    public Connection currentConnection() {
        return mConnection;
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
            detail = DiagnosticMessages.BRIDGE_NOT_INSTALLED_DETAIL;
            return ServiceDiagnostic.of(ServiceDiagnostic.Type.BRIDGE_UNAVAILABLE, detail);
        } else if (status != null && !status.systemServer) {
            detail = DiagnosticMessages.BRIDGE_NOT_IN_SYSTEM_SERVER_DETAIL;
            return ServiceDiagnostic.of(ServiceDiagnostic.Type.BRIDGE_UNAVAILABLE, detail);
        }
        detail = error == null || error.trim().isEmpty()
                ? DiagnosticMessages.BRIDGE_READY_SERVICE_UNREGISTERED_DETAIL : error;
        return status == null
                ? ServiceDiagnostic.of(ServiceDiagnostic.Type.BRIDGE_UNAVAILABLE, detail)
                : ServiceDiagnostic.of(ServiceDiagnostic.Type.SERVICE_STARTING, detail);
    }

    static boolean isExpectedIdentity(ServiceIdentityParcel identity) {
        return identity != null
                && identity.protocolVersion == RuleServiceContract.PROTOCOL_VERSION
                && identity.buildVersionCode == RuleServiceContract.BUILD_VERSION_CODE
                && RuleServiceContract.CONTRACT_FINGERPRINT.equals(identity.contractFingerprint);
    }

    public String getLastError() { return mLastError; }
    public ServiceDiagnostic getServiceDiagnostic() { return mServiceDiagnostic; }
    public String getServiceFailureMessage() {
        ServiceDiagnostic diagnostic = mServiceDiagnostic;
        return diagnostic == null ? null : diagnostic.getUserMessage();
    }

    public void recordDiagnostic(ServiceDiagnostic diagnostic) {
        mServiceDiagnostic = diagnostic;
        if (diagnostic == null) {
            mLastError = null;
            return;
        }
        String detail = diagnostic.getTechnicalDetail();
        mLastError = detail == null || detail.trim().isEmpty()
                ? diagnostic.getSummary() : detail;
    }

    public void clearDiagnostic() {
        recordDiagnostic(null);
    }

    public void recordResultFailure(int status, String detail) {
        recordDiagnostic(ServiceDiagnostic.forResultStatus(status, detail));
    }

    /** mutate 保存/恢复诊断现场（closeLease 可能覆盖诊断），原语义直写两字段。 */
    public void restoreDiagnostic(ServiceDiagnostic diagnostic, String lastError) {
        mServiceDiagnostic = diagnostic;
        mLastError = lastError;
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

    private Connection flushReadyViaBridge(Connection connection) {
        LogBridge bridge = mLogBridge;
        if (bridge == null) return connection;
        return bridge.flushReadyPendingLogs(connection);
    }

    private RemoteException flushViaBridge(Connection connection) {
        LogBridge bridge = mLogBridge;
        if (bridge == null) return null;
        return bridge.flushPendingLogs(connection);
    }

    public void logError(String method, Connection connection, RemoteException e) {
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

    public static RemoteException asRemote(Exception e) {
        RemoteException remote = new RemoteException(e.getMessage());
        remote.initCause(e);
        return remote;
    }

    public boolean hasLight() {
        Connection c = ensureConnection(); if (c == null) return false;
        try { return c.service.hasLight(); } catch (RemoteException e) { logError("hasLight", c, e); return false; }
    }

    public void logMutationTerminal(int operation, String packageName, String requestId,
                                    int status, String outcome) {
        String line = "mutation client complete operation=" + mutationOperationName(operation)
                + " requestId=" + requestId + " package=" + packageName
                + " status=" + mutationStatusName(status) + " outcome=" + outcome;
        if (RuleServiceContract.isTerminalSuccess(status)) {
            Logger.i(TAG, line);
        } else if (RuleServiceContract.isRetryableTransient(status)) {
            Logger.w(TAG, line);
        } else if (status == RuleServiceContract.RESULT_REJECTED) {
            // 拒绝多为权限/归属问题，是线上排障的关键信号，禁止淹没在 debug 中。
            // （BUSY 已在上一分支按瞬态处理为 warning，不会落到这里。）
            Logger.w(TAG, line);
        } else {
            Logger.d(TAG, line);
        }
    }

    public static String mutationOperationName(int operation) {
        switch (operation) {
            case RuleServiceContract.MUTATION_WRITE: return "write";
            case RuleServiceContract.MUTATION_UPDATE: return "update";
            case RuleServiceContract.MUTATION_DELETE: return "delete";
            case RuleServiceContract.MUTATION_DELETE_ALL: return "delete_all";
            case RuleServiceContract.MUTATION_SET_TOOLBAR: return "set_toolbar";
            default: return "unknown(" + operation + ")";
        }
    }

    public static String mutationStatusName(int status) {
        switch (status) {
            case RuleServiceContract.RESULT_COMMITTED: return "committed";
            case RuleServiceContract.RESULT_NO_CHANGE: return "no_change";
            case RuleServiceContract.RESULT_BUSY: return "busy";
            case RuleServiceContract.RESULT_REJECTED: return "rejected";
            case RuleServiceContract.RESULT_WRITE_FAILED: return "write_failed";
            case RuleServiceContract.RESULT_REBOOT_REQUIRED: return "reboot_required";
            case RuleServiceContract.RESULT_INVALID: return "invalid";
            case RuleServiceContract.RESULT_UNCERTAIN: return "uncertain";
            case RuleServiceContract.RESULT_STALE: return "stale";
            case RuleServiceContract.RESULT_EXPIRED: return "expired";
            case RuleServiceContract.RESULT_OWNER_MISMATCH: return "owner_mismatch";
            case RuleServiceContract.RESULT_ALREADY_UNDONE: return "already_undone";
            default: return "unknown(" + status + ")";
        }
    }
}
