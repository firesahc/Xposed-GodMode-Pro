package com.kaisar.xposed.godmode.ipc;

import android.graphics.Bitmap;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xservicemanager.XServiceManager;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AIDL 客户端门面 — 通过 XServiceManager 桥接与 system_server 中的 RuleServiceServer 通信。
 * <p>
 * 提供 Binder 连接管理（断连重试、死亡监听）、所有 IPC 方法代理、连通性诊断。
 * 整个应用中唯一直接与 Binder 打交道的客户端类。
 * <p>
 * 使用 {@link #getDefault()} 获取进程级单例。
 */
public final class RuleServiceClient {

    private static final String TAG = "RuleServiceClient";
    private static final IGodModeManager FALLBACK = new IGodModeManager.Default();
    private static final int CONNECT_RETRY_COUNT = 3;
    private static final long[] CONNECT_RETRY_DELAYS_MS = {80L, 160L};

    private static volatile RuleServiceClient instance;
    private volatile IGodModeManager mGMM;
    private volatile IBinder mBinder;
    private volatile String mLastError;
    private final CopyOnWriteArrayList<Runnable> mBinderDeathListeners = new CopyOnWriteArrayList<>();
    /** Local observer registrations survive Binder death and are replayed on reconnect. */
    private final CopyOnWriteArrayList<ObserverSubscription> mObserverSubscriptions =
            new CopyOnWriteArrayList<>();

    private RuleServiceClient() {
    }

    public static RuleServiceClient getDefault() {
        RuleServiceClient result = instance;
        if (result == null) {
            synchronized (RuleServiceClient.class) {
                result = instance;
                if (result == null) {
                    result = new RuleServiceClient();
                    result.ensureService();
                    instance = result;
                }
            }
        }
        return result;
    }

    private IGodModeManager ensureService() {
        IGodModeManager current = mGMM;
        IBinder binder = mBinder;
        if (current != null && binder != null && binder.isBinderAlive()) {
            return current;
        }
        synchronized (this) {
            current = mGMM;
            binder = mBinder;
            if (current != null && binder != null && binder.isBinderAlive()) {
                return current;
            }
            IBinder service = connectWithRetry();
            if (service == null) {
                mBinder = null;
                mGMM = null;
                mLastError = buildBridgeError();
                Logger.e(TAG, mLastError);
                return FALLBACK;
            }
            try {
                service.linkToDeath(() -> {
                    synchronized (RuleServiceClient.this) {
                        if (mBinder == service) {
                            mBinder = null;
                            mGMM = null;
                            mLastError = "godmode 服务 Binder 已死亡，等待下次重连";
                        }
                    }
                    Logger.w(TAG, "godmode service binder died, will reconnect on next call");
                    notifyBinderDead();
                }, 0);
            } catch (RemoteException e) {
                mBinder = null;
                mGMM = null;
                mLastError = "godmode 服务在注册死亡监听前已失效: " + e.getMessage();
                Logger.w(TAG, "godmode service died before linkToDeath", e);
                notifyBinderDead();
                return FALLBACK;
            }
            mBinder = service;
            mGMM = IGodModeManager.Stub.asInterface(service);
            mLastError = null;
            Logger.i(TAG, "connected to godmode service via clipboard delegate");
            reregisterObservers(mGMM);
            return mGMM;
        }
    }

    private void reregisterObservers(IGodModeManager service) {
        for (ObserverSubscription subscription : mObserverSubscriptions) {
            try {
                service.addObserver(subscription.packageName, subscription.observer);
            } catch (RemoteException e) {
                // Keep the local registration. A later reconnect will retry it.
                Logger.w(TAG, "observer re-register failed for "
                        + subscription.packageName, e);
            }
        }
    }

    private IBinder connectWithRetry() {
        IBinder service = null;
        for (int i = 0; i < CONNECT_RETRY_COUNT; i++) {
            if (!XServiceManager.pingBridge()) {
                mLastError = buildBridgeError();
            } else {
                service = XServiceManager.getService("godmode");
                if (service != null) {
                    return service;
                }
                mLastError = buildBridgeError();
            }
            if (i < CONNECT_RETRY_DELAYS_MS.length) {
                sleepQuietly(CONNECT_RETRY_DELAYS_MS[i]);
            }
        }
        return service;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String buildBridgeError() {
        String serviceError = XServiceManager.getLastError();
        XServiceManager.BridgeStatus status = XServiceManager.getRemoteBridgeStatus();
        if (status != null) {
            if (!status.bridgeInstalled) {
                return "XServiceManager 桥接未安装，请确认模块已在 LSPosed 的 android 作用域启用并重启系统。";
            }
            if (!status.systemServer) {
                return "XServiceManager 桥接没有运行在 system_server，当前注入进程异常。";
            }
            if (status.registeredServiceCount == 0) {
                return "XServiceManager 桥接已安装，但 godmode 服务尚未注册。";
            }
            if (status.lastError != null && !status.lastError.trim().isEmpty()) {
                return "XServiceManager 桥接异常: " + status.lastError;
            }
        }
        String error = serviceError;
        if (error == null || error.trim().isEmpty()) {
            error = XServiceManager.getLastError();
        }
        if (error == null || error.trim().isEmpty()) {
            return "XServiceManager 桥接不可用，可能是模块未在 android 作用域启用或 system_server 尚未完成注入。";
        }
        if (error.contains("clipboard is null")) {
            return "系统 clipboard 服务不可访问，XServiceManager 无法建立桥接。";
        }
        if (error.contains("did not handle XServiceManager ping")
                || error.contains("did not handle XServiceManager transaction")
                || error.contains("did not handle XServiceManager status")) {
            return "XServiceManager 私有事务未被 clipboard 桥接处理，请确认模块已启用并重启系统。";
        }
        if (error.contains("service godmode is not registered")) {
            return "XServiceManager 桥接已连接，但 godmode 服务未注册。";
        }
        return "XServiceManager 桥接异常: " + error;
    }

    public String getLastError() {
        String error = mLastError;
        if (error == null || error.trim().isEmpty()) {
            error = XServiceManager.getLastError();
        }
        return error;
    }

    public boolean isConnected() {
        IBinder binder = mBinder;
        return mGMM != null && binder != null && binder.isBinderAlive();
    }

    public void addBinderDeathListener(Runnable listener) {
        if (listener != null && !mBinderDeathListeners.contains(listener)) {
            mBinderDeathListeners.add(listener);
        }
    }

    public void removeBinderDeathListener(Runnable listener) {
        if (listener != null) {
            mBinderDeathListeners.remove(listener);
        }
    }

    private void notifyBinderDead() {
        for (Runnable listener : mBinderDeathListeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                Logger.w(TAG, "binder death listener failed", t);
            }
        }
    }

    private void markServiceDead() {
        synchronized (this) {
            mBinder = null;
            mGMM = null;
        }
        notifyBinderDead();
    }

    private void logError(String method, RemoteException e) {
        IBinder binder = mBinder;
        if (e instanceof DeadObjectException || binder == null || !binder.isBinderAlive()) {
            markServiceDead();
        }
        mLastError = "RuleServiceClient#" + method + " 调用失败: " + e.getMessage();
        Logger.e(TAG, "RuleServiceClient#" + method + " failed: " + e.getMessage());
    }

    public boolean hasLight() {
        try {
            return ensureService().hasLight();
        } catch (RemoteException e) {
            logError("hasLight", e);
            return false;
        }
    }

    public void setEditMode(boolean enable) {
        try {
            ensureService().setEditMode(enable);
        } catch (RemoteException e) {
            logError("setEditMode", e);
        }
    }

    public boolean isInEditMode() {
        try {
            return ensureService().isInEditMode();
        } catch (RemoteException e) {
            logError("isInEditMode", e);
            return false;
        }
    }

    public void addObserver(String packageName, IObserver observer) {
        if (packageName == null || observer == null) return;
        ObserverSubscription subscription = new ObserverSubscription(packageName, observer);
        if (!mObserverSubscriptions.contains(subscription)) {
            mObserverSubscriptions.add(subscription);
        } else {
            return;
        }
        try {
            ensureService().addObserver(packageName, observer);
        } catch (RemoteException e) {
            logError("addObserver", e);
        }
    }

    public void removeObserver(String packageName, IObserver observer) {
        if (packageName == null || observer == null) return;
        mObserverSubscriptions.remove(new ObserverSubscription(packageName, observer));
        try {
            ensureService().removeObserver(packageName, observer);
        } catch (RemoteException e) {
            logError("removeObserver", e);
        }
    }

    public AppRules getAllRules() {
        try {
            return ensureService().getAllRules();
        } catch (RemoteException e) {
            logError("getAllRules", e);
            return new AppRules();
        } catch (RuntimeException e) {
            logError("getAllRules", new RemoteException(e.getMessage()));
            return new AppRules();
        }
    }

    public ActRules getRules(String packageName) {
        try {
            return ensureService().getRules(packageName);
        } catch (RemoteException e) {
            logError("getRules", e);
            return null;
        } catch (RuntimeException e) {
            logError("getRules", new RemoteException(e.getMessage()));
            return null;
        }
    }

    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap bitmap) {
        try {
            return ensureService().writeRule(packageName, viewRule, bitmap);
        } catch (RemoteException e) {
            logError("writeRule", e);
            return false;
        }
    }

    public boolean updateRule(String packageName, RuleRecord viewRule) {
        try {
            return ensureService().updateRule(packageName, viewRule);
        } catch (RemoteException e) {
            logError("updateRule", e);
            return false;
        }
    }

    public boolean deleteRule(String packageName, RuleRecord viewRule) {
        try {
            return ensureService().deleteRule(packageName, viewRule);
        } catch (RemoteException e) {
            logError("deleteRule", e);
            return false;
        }
    }

    public boolean deleteRules(String packageName) {
        try {
            return ensureService().deleteRules(packageName);
        } catch (RemoteException e) {
            logError("deleteRules", e);
            return false;
        }
    }

    public ParcelFileDescriptor openImageFileDescriptor(String filePath) {
        try {
            return ensureService().openImageFileDescriptor(filePath);
        } catch (RemoteException e) {
            logError("openImageFileDescriptor", e);
            return null;
        }
    }

    public String saveImageFile(String packageName, Bitmap bitmap) {
        try {
            return ensureService().saveImageFile(packageName, bitmap);
        } catch (RemoteException e) {
            logError("saveImageFile", e);
            return null;
        }
    }

    public String getToolbarHiddenItems() {
        try {
            return ensureService().getToolbarHiddenItems();
        } catch (RemoteException e) {
            logError("getToolbarHiddenItems", e);
            return "";
        }
    }

    public void setToolbarHiddenItems(String items) {
        try {
            ensureService().setToolbarHiddenItems(items);
        } catch (RemoteException e) {
            logError("setToolbarHiddenItems", e);
        }
    }

    // ---- 日志转发（Logger.Writer 回调）----

    /**
     * 通过 IPC 向 system_server 转发一条日志。
     * 此方法仅供 {@link com.kaisar.xposed.godmode.engine.util.Logger.Writer} 使用，
     * 内部直接用 Logger.w 处理异常（forwardLog 走 logcat 通道，不触发 Writer 回调，无递归风险）。
     */
    public void forwardLog(int level, String tag, String msg, long timestamp) {
        forwardLog("unknown", level, tag, msg, timestamp);
    }

    public void forwardLog(String packageName, int level, String tag, String msg, long timestamp) {
        try {
            ensureService().log(level, packageName != null ? packageName : "unknown",
                    timestamp, tag, msg);
        } catch (RemoteException e) {
            Logger.w(TAG, "forwardLog IPC failed: " + e.getMessage());
        } catch (Throwable t) {
            Logger.w(TAG, "forwardLog unexpected error", t);
        }
    }

    private static final class ObserverSubscription {
        final String packageName;
        final IObserver observer;

        ObserverSubscription(String packageName, IObserver observer) {
            this.packageName = packageName;
            this.observer = observer;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ObserverSubscription)) return false;
            ObserverSubscription that = (ObserverSubscription) other;
            return packageName.equals(that.packageName)
                    && observer.asBinder() == that.observer.asBinder();
        }

        @Override
        public int hashCode() {
            return 31 * packageName.hashCode()
                    + System.identityHashCode(observer.asBinder());
        }
    }
}
