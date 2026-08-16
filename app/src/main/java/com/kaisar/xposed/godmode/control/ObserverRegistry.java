package com.kaisar.xposed.godmode.control;

import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 观察者管理 — RemoteCallbackList 注册 + 死观察者清理 + 事件通知。
 * <p>
 * 从 {@code service/} 移入 control/ 包，职责不变。
 */
public final class ObserverRegistry {

    /** 死观察者自动清理间隔(ms) */
    static final long CLEAN_INTERVAL = 60_000L;

    private final RemoteCallbackList<ObserverProxy> mRemoteCallbackList = new RemoteCallbackList<>();
    private final HashMap<String, HashMap<IBinder, ObserverProxy>> mRegisteredObserverMap = new HashMap<>();
    private final Logger mLogger;
    private final Handler mHandle;
    private final int mCleanObserversMsgCode;

    /**
     * @param logger               日志记录器
     * @param handle               Handler 用于调度清理任务
     * @param cleanObserversMsgCode Handler 消息代码
     */
    public ObserverRegistry(Logger logger, Handler handle, int cleanObserversMsgCode) {
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCleanObserversMsgCode = cleanObserversMsgCode;
    }

    // ---- 观察者注册/注销 ----

    /**
     * 注册观察者，首次注册时立即通知当前状态。
     */
    public void addObserver(String packageName, IObserver observer, boolean editModeEnabled,
            ActRules currentRules) {
        synchronized (mRemoteCallbackList) {
            synchronized (mRegisteredObserverMap) {
                IBinder binder = observer.asBinder();
                HashMap<IBinder, ObserverProxy> packageObservers =
                        mRegisteredObserverMap.computeIfAbsent(packageName, k -> new HashMap<>());
                if (packageObservers.containsKey(binder)) {
                    return;
                }
                ObserverProxy proxy = new ObserverProxy(packageName, observer);
                packageObservers.put(binder, proxy);
                mRemoteCallbackList.register(proxy);
            }
            scheduleDeadObserverCleanup();
        }
        // 立即通知新注册的观察者当前状态
        try {
            observer.onEditModeChanged(editModeEnabled);
            // LOADING/ERROR is represented by null.  Do not turn an unavailable
            // repository into a fake empty snapshot; the ready callback below
            // will deliver the first authoritative snapshot.
            if (currentRules != null) {
                observer.onViewRuleChanged(packageName, currentRules);
            }
        } catch (RemoteException e) {
            mLogger.w("immediate notify observer failed", e);
        }
    }

    /** 注销观察者 */
    public void removeObserver(String packageName, IObserver observer) {
        synchronized (mRemoteCallbackList) {
            ObserverProxy proxy = null;
            synchronized (mRegisteredObserverMap) {
                HashMap<IBinder, ObserverProxy> packageObservers =
                        mRegisteredObserverMap.get(packageName);
                if (packageObservers != null) {
                    proxy = packageObservers.remove(observer.asBinder());
                    if (packageObservers.isEmpty()) {
                        mRegisteredObserverMap.remove(packageName);
                    }
                }
            }
            if (proxy != null) {
                mRemoteCallbackList.unregister(proxy);
            } else {
                mRemoteCallbackList.unregister(new ObserverProxy(packageName, observer));
            }
        }
    }

    // ---- 事件通知 ----

    public void notifyObserverRuleChanged(String packageName, ActRules actRules) {
        forEachLiveObserver((proxy) -> {
            if (TextUtils.equals(proxy.packageName, packageName)
                    || TextUtils.equals(proxy.packageName, "*")) {
                proxy.observer.onViewRuleChanged(packageName, actRules);
            }
        });
    }

    public void notifyObserverEditModeChanged(boolean enable) {
        forEachLiveObserver((proxy) -> proxy.onEditModeChanged(enable));
    }

    /**
     * Publishes the first authoritative repository snapshot after loading.
     * A wildcard observer receives a refresh signal and is expected to call
     * getAllRules(), while package observers receive their complete snapshot.
     */
    public void notifyRulesLoaded(AppRules appRules) {
        forEachLiveObserver((proxy) -> {
            if (TextUtils.equals(proxy.packageName, "*")) {
                proxy.observer.onViewRuleChanged("*", new ActRules());
                return;
            }
            ActRules rules = appRules != null ? appRules.get(proxy.packageName) : null;
            proxy.observer.onViewRuleChanged(proxy.packageName,
                    rules != null ? rules : new ActRules());
        });
    }

    // ---- 死观察者清理 ----

    void scheduleDeadObserverCleanup() {
        if (!mHandle.hasMessages(mCleanObserversMsgCode)) {
            mHandle.sendEmptyMessageDelayed(
                    mCleanObserversMsgCode, CLEAN_INTERVAL);
        }
    }

    public void cleanDeadObservers() {
        synchronized (mRemoteCallbackList) {
            int N = mRemoteCallbackList.beginBroadcast();
            List<ObserverProxy> dead = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                ObserverProxy proxy = mRemoteCallbackList.getBroadcastItem(i);
                if (proxy == null || !proxy.observer.asBinder().pingBinder()) {
                    dead.add(proxy);
                }
            }
            mRemoteCallbackList.finishBroadcast();
            for (ObserverProxy proxy : dead) {
                if (proxy != null) {
                    try {
                        mRemoteCallbackList.unregister(proxy);
                        synchronized (mRegisteredObserverMap) {
                            HashMap<IBinder, ObserverProxy> packageObservers =
                                    mRegisteredObserverMap.get(proxy.packageName);
                            if (packageObservers != null) {
                                packageObservers.remove(proxy.observer.asBinder());
                                if (packageObservers.isEmpty()) {
                                    mRegisteredObserverMap.remove(proxy.packageName);
                                }
                            }
                        }
                        mLogger.d("cleaned dead observer: " + proxy.packageName);
                    } catch (Exception e) {
                        mLogger.w("clean dead observer failed", e);
                    }
                }
            }
        }
    }

    // ---- 工具方法 ----

    private void forEachLiveObserver(ObserverAction action) {
        synchronized (mRemoteCallbackList) {
            final int N = mRemoteCallbackList.beginBroadcast();
            for (int i = 0; i < N; i++) {
                try {
                    ObserverProxy proxy = mRemoteCallbackList.getBroadcastItem(i);
                    if (proxy != null && proxy.observer.asBinder().pingBinder()) {
                        action.execute(proxy);
                    }
                } catch (Exception e) {
                    mLogger.w("notify observer failed", e);
                }
            }
            mRemoteCallbackList.finishBroadcast();
        }
    }

    private interface ObserverAction {
        void execute(ObserverProxy proxy) throws RemoteException;
    }

    // ---- ObserverProxy ----

    static final class ObserverProxy implements IObserver {
        final String packageName;
        final IObserver observer;

        ObserverProxy(String packageName, IObserver observer) {
            this.packageName = packageName;
            this.observer = observer;
        }

        @Override
        public void onEditModeChanged(boolean enable) throws RemoteException {
            observer.onEditModeChanged(enable);
        }

        @Override
        public void onViewRuleChanged(String packageName, ActRules actRules)
                throws RemoteException {
            observer.onViewRuleChanged(packageName, actRules);
        }

        @Override
        public IBinder asBinder() {
            return observer.asBinder();
        }
    }
}
