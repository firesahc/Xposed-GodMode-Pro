package com.kaisar.xposed.godmode.ipc;

import android.os.RemoteException;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xservicemanager.XServiceManager;

import java.util.ArrayDeque;

/**
 * B2 日志桥：持有进程日志 backlog 全状态与队列/发送语义。
 *
 * <p>逐行平移自 RuleServiceClient（有界队列512/溢出保留最新/恢复报丢数/
 * 时间戳原值补发）：连接态单源（构造注入的 ServiceConnection，不新开单例），
 * 只记日志不抛宿主。Client 保留 installProcessLogging/forwardLog 公开签名转发，
 * 核内 flushReadyPendingLogs/flushPendingLogs 转发点调本实现。
 */
public final class LogBridge implements ServiceConnection.LogBridge {
    private static final String TAG = "RuleServiceClient";
    private static final int MAX_PENDING_LOGS = 512;

    private final ServiceConnection mServiceConnection;
    private final Object mLogLock = new Object();
    private final ArrayDeque<PendingLog> mPendingLogs = new ArrayDeque<>(MAX_PENDING_LOGS);
    private long mDroppedPendingLogs;
    private long mRejectedPendingLogs;

    private static volatile LogBridge sInstance;

    /** 职责门面直调入口：真单例，直连 {@link ServiceConnection#getDefault()} 并回装核内转发点。 */
    public static LogBridge getDefault() {
        LogBridge result = sInstance;
        if (result == null) {
            synchronized (LogBridge.class) {
                result = sInstance;
                if (result == null) {
                    result = new LogBridge(ServiceConnection.getDefault());
                    ServiceConnection.getDefault().setLogBridge(result);
                    sInstance = result;
                }
            }
        }
        return result;
    }

    public LogBridge(ServiceConnection serviceConnection) {
        mServiceConnection = serviceConnection;
    }

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

    public void forwardLog(int level, String tag, String msg, long timestamp) {
        forwardLog("unknown", level, tag, msg, timestamp);
    }

    public void forwardLog(String packageName, int level, String tag, String msg, long timestamp) {
        // Logging never establishes Binder synchronously. While Binder is unavailable, retain a
        // bounded process-local backlog so handshake/death-window diagnostics can be flushed by
        // the next successful connection instead of disappearing silently.
        PendingLog pending = new PendingLog(packageName == null ? "unknown" : packageName,
                level, tag, msg, timestamp);
        ServiceConnection.Connection c = mServiceConnection.currentConnection();
        RemoteException failure = null;
        synchronized (mLogLock) {
            if (!mServiceConnection.isReady(c) || !mPendingLogs.isEmpty()) {
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
        if (failure != null) mServiceConnection.logError("log", c, failure);
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

    @Override
    public ServiceConnection.Connection flushReadyPendingLogs(ServiceConnection.Connection connection) {
        synchronized (mLogLock) {
            if (mPendingLogs.isEmpty()) return connection;
        }
        RemoteException failure = flushPendingLogs(connection);
        if (failure != null) {
            mServiceConnection.logError("flushLogs", connection, failure);
            return mServiceConnection.isReady(connection) ? connection : null;
        }
        return connection;
    }

    @Override
    public RemoteException flushPendingLogs(ServiceConnection.Connection connection) {
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

    private static void sendLog(ServiceConnection.Connection connection, PendingLog pending) throws RemoteException {
        connection.service.log(pending.level, pending.packageName, pending.timestamp,
                pending.tag, pending.message);
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
}
