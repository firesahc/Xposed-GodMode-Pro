package com.kaisar.xposed.godmode.ipc;

import android.os.RemoteException;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.contract.ILeaseOwner;
import com.kaisar.xposed.godmode.ipc.contract.OperationLeaseParcel;

/**
 * B3 写入团租约枢纽：持有全部操作租约句柄（编辑租约由调用方经 open/close 配对持有，
 * 恢复/备份长租约常驻本 Hub），构造注入 {@link ServiceConnection}。
 *
 * <p>行为零差抽取自 RuleServiceClient（openLease/closeLease/mLeaseOwner 三方死亡清理，
 * BUSY 关闭置 CLOSING 标记语义不变），只做租约机制，不碰编辑投影语义：
 * {@code ClientEditState} 留在 Client 侧（它是观察者链路的编辑投影状态，随连接
 * epoch 推进而接受/丢弃），Hub 经 {@link Listener} 回调通知 Client 清理投影，
 * 避免投影与租约机制耦合。
 */
public final class LeaseHub {
    private static final String TAG = "RuleServiceClient";

    /**
     * 租约事件回调，由 Client 实现。所有回调只做状态清理，不得阻塞、不得再对
     * Client 加锁（openLease/closeLease 可能在连接锁内触发，只读快照即可）。
     */
    public interface Listener {
        /** 当前编辑投影是否使能（仅用于撤销日志诊断快照）。 */
        boolean isEditEnabled();
        /** 当前编辑投影 revision（仅用于撤销日志诊断快照）。 */
        long editRevision();
        /** 服务端撤销租约：Hub 已丢弃恢复/备份句柄，Client 侧重置编辑投影。 */
        void onEditLeaseRevoked();
        /** close 返回 BUSY：若 token 仍是编辑租约，Client 侧标记 CLOSING。 */
        void onLeaseCloseBusy(String token);
    }

    private final ServiceConnection mServiceConnection;
    private volatile Listener mListener;
    private volatile String mRestoreLease;
    private volatile String mBackupLease;
    private final ILeaseOwner mLeaseOwner = new ILeaseOwner.Stub() {
        @Override public void onLeaseRevoked(int reason) {
            boolean hadRestoreLease = mRestoreLease != null;
            boolean hadBackupLease = mBackupLease != null;
            Listener listener = mListener;
            boolean wasEditEnabled = listener != null && listener.isEditEnabled();
            long editRevision = listener == null ? -1L : listener.editRevision();
            long epoch = mServiceConnection.getConnectionEpoch();
            mRestoreLease = null;
            mBackupLease = null;
            if (listener != null) listener.onEditLeaseRevoked();
            Logger.w(TAG, "operation lease revoked reason=" + reason
                    + " epoch=" + epoch + " editEnabled=" + wasEditEnabled
                    + " editRevision=" + editRevision
                    + " hadRestoreLease=" + hadRestoreLease
                    + " hadBackupLease=" + hadBackupLease);
        }
    };

    public LeaseHub(ServiceConnection serviceConnection) {
        if (serviceConnection == null) throw new IllegalArgumentException("serviceConnection is required");
        mServiceConnection = serviceConnection;
    }

    /** 职责门面直调入口：真单例，直连 {@link ServiceConnection#getDefault()}。 */
    private static volatile LeaseHub sInstance;

    public static LeaseHub getDefault() {
        LeaseHub result = sInstance;
        if (result == null) {
            synchronized (LeaseHub.class) {
                result = sInstance;
                if (result == null) {
                    result = new LeaseHub(ServiceConnection.getDefault());
                    sInstance = result;
                }
            }
        }
        return result;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public ILeaseOwner getLeaseOwner() {
        return mLeaseOwner;
    }

    /** 恢复长租约句柄（mutate 复用，不转移所有权），可为 null。 */
    public String peekRestoreLease() {
        return mRestoreLease;
    }

    /** 备份长租约句柄，可为 null。 */
    public String peekBackupLease() {
        return mBackupLease;
    }

    public String openLease(int type, String packageName) {
        ServiceConnection.Connection c = mServiceConnection.ensureConnection(); if (c == null) return null;
        try {
            OperationLeaseParcel lease = c.service.openOperation(type, packageName, mLeaseOwner);
            if (lease != null && lease.status == RuleServiceContract.RESULT_COMMITTED) {
                mServiceConnection.clearDiagnostic();
                return lease.token;
            }
            if (lease == null) {
                mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                        DiagnosticMessages.OPERATION_LEASE_MISSING_DETAIL));
            } else {
                mServiceConnection.recordResultFailure(lease.status, lease.message);
            }
            return null;
        } catch (RemoteException e) { mServiceConnection.logError("openOperation", c, e); return null; }
    }

    public boolean closeLease(String token) {
        ServiceConnection.Connection c = mServiceConnection.ensureConnection(); if (c == null) return false;
        try {
            OperationLeaseParcel result = c.service.closeOperation(token, mLeaseOwner);
            boolean closed = result != null
                    && (result.status == RuleServiceContract.RESULT_COMMITTED
                    || result.status == RuleServiceContract.RESULT_NO_CHANGE);
            if (closed) mServiceConnection.clearDiagnostic();
            if (!closed) {
                if (result == null) {
                    mServiceConnection.recordDiagnostic(ServiceDiagnostic.of(ServiceDiagnostic.Type.UNKNOWN,
                            DiagnosticMessages.OPERATION_CLOSE_RESULT_MISSING_DETAIL));
                } else {
                    mServiceConnection.recordResultFailure(result.status, result.message);
                }
                if (result != null && result.status == RuleServiceContract.RESULT_BUSY) {
                    Listener listener = mListener;
                    if (listener != null) listener.onLeaseCloseBusy(token);
                }
            }
            return closed;
        }
        catch (RemoteException e) { mServiceConnection.logError("closeOperation", c, e); return false; }
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

    /**
     * 连接清空/死亡时丢弃恢复/备份句柄。编辑投影由 Client 侧自行重置
     *（见 Client 的 ConnectionListener），本方法只管租约句柄，不管投影语义。
     */
    public void clearLeases() {
        mRestoreLease = null;
        mBackupLease = null;
    }
}
