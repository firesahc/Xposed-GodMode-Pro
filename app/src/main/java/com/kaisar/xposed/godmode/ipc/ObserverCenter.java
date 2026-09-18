package com.kaisar.xposed.godmode.ipc;

import android.os.RemoteException;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.contract.IRuleObserver;
import com.kaisar.xposed.godmode.ipc.contract.ObserverRegistrationParcel;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * B5 观察者门面：订阅注册/重注册、epoch/世代校验、编辑会话投影
 * （ClientEditState）与 setEditMode/isEdit* 全家的唯一归属，构造注入
 * {@link ServiceConnection}、{@link LeaseHub} 与 {@link RuleReader}。
 *
 * <p>行为零差搬迁自 RuleServiceClient（340-550 区订阅/注册/epoch 校验、
 * 465-490 add/remove、536-541 事件谓词逐行保留；Relay 回调仍在 Binder
 * 线程同步派发，主线程切换由各 ObserverCallback 实现方负责，语义不变）。
 *
 * <p>归属决策（ClientEditState 随观察者走）：编辑投影是观察者链路的
 * 权威编辑会话在 Client 侧的投影，随连接 epoch 推进而接受/丢弃
 * （acceptEditState/reset/markClosing 全部由观察者事件与连接回调驱动），
 * 与 B3 租约机制（LeaseHub 只管租约句柄不管投影语义）的边界一致；
 * 故投影实例迁入本中心，Client 只读消费经转发，写（setEditMode）亦归
 * 本中心（租约 open/close 配对经 LeaseHub）。
 */
public final class ObserverCenter {
    private static final String TAG = "RuleServiceClient";

    private final ServiceConnection mServiceConnection;
    private final LeaseHub mLeaseHub;
    private final RuleReader mRuleReader;
    private final CopyOnWriteArrayList<ObserverSubscription> mObserverSubscriptions = new CopyOnWriteArrayList<>();
    private final ClientEditState mEditState = new ClientEditState();

    public ObserverCenter(ServiceConnection serviceConnection, LeaseHub leaseHub,
                          RuleReader ruleReader) {
        if (serviceConnection == null) throw new IllegalArgumentException("serviceConnection is required");
        if (leaseHub == null) throw new IllegalArgumentException("leaseHub is required");
        if (ruleReader == null) throw new IllegalArgumentException("ruleReader is required");
        mServiceConnection = serviceConnection;
        mLeaseHub = leaseHub;
        mRuleReader = ruleReader;
        // 投影与租约机制解耦：Hub 经 Listener 回调通知本中心清理投影。
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
    }

    /** 职责门面直调入口（与 Client 共享同一连接核，不分裂 epoch）。 */
    public static ObserverCenter getDefault() {
        return RuleServiceClient.getDefault().getObserverCenter();
    }

    /** 连接清空时清理投影与远端句柄（由 Client 的 ConnectionListener 转调）。 */
    public void onConnectionCleared() {
        mEditState.reset();
        mRuleReader.resetGeneration();
        for (ObserverSubscription subscription : mObserverSubscriptions) {
            subscription.clearRemote();
        }
    }

    /** 建连成功时重注册观察者（由 Client 的 ConnectionListener 转调）。 */
    public void onConnectionEstablished(ServiceConnection.Connection connection) {
        reregisterObservers(connection);
    }

    public void reregisterObservers(ServiceConnection.Connection connection) {
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
                mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.OPERATION_BUSY,
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

    /** 投影 revision，供写入对账（RuleEditorClient.Host）共用。 */
    public long editRevision() {
        return mEditState.revision();
    }

    private boolean hasReadyConnection() {
        return mServiceConnection.hasReadyConnection();
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
        ServiceConnection.Connection c = mServiceConnection.ensureConnection();
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
        catch (RemoteException e) { mServiceConnection.logError("addObserver", c, e); }
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
        ServiceConnection.Connection c = mServiceConnection.ensureConnection();
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
        catch (RemoteException e) { mServiceConnection.logError("removeObserver", c, e); }
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
        return mRuleReader.acceptRuleGeneration(epoch, generation);
    }

    public boolean isCurrentEditEvent(long epoch, long revision) {
        return ClientEventOrder.isCurrent(epoch, mServiceConnection.getConnectionEpoch(),
                revision, mEditState.revision()) && isCurrentEpoch(epoch);
    }

    public boolean isCurrentRuleEvent(long epoch, long generation) {
        return ClientEventOrder.isCurrent(epoch, mServiceConnection.getConnectionEpoch(),
                generation, mRuleReader.ruleGeneration()) && isCurrentEpoch(epoch);
    }

    private boolean isCurrentEpoch(long epoch) {
        return mServiceConnection.isCurrentEpoch(epoch);
    }

    private boolean isCurrentEpochLocked(long epoch) {
        return mServiceConnection.isCurrentEpoch(epoch);
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
