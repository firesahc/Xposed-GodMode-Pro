package com.kaisar.xposed.godmode.service;

import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 观察者管理 — RemoteCallbackList 注册 + 死观察者清理 + 事件通知。
 * 由 RuleServiceServer 使用。
 */
final class ObserverRegistry {

    /** 死观察者自动清理间隔(ms) */
    static final long CLEAN_INTERVAL = 60_000L;

    private final RemoteCallbackList<ObserverProxy> mRemoteCallbackList = new RemoteCallbackList<>();
    private final HashMap<String, IBinder> mRegisteredObserverMap = new HashMap<>();
    private final Logger mLogger;
    private final Handler mHandle;
    private final int mCleanObserversMsgCode;

    /**
     * @param logger               日志记录器
     * @param handle               Handler 用于调度清理任务
     * @param cleanObserversMsgCode Handler 消息代码（由 RuleServiceServer 定义）
     */
    ObserverRegistry(Logger logger, Handler handle, int cleanObserversMsgCode) {
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCleanObserversMsgCode = cleanObserversMsgCode;
    }

    // ---- 观察者注册/注销 ----

    /**
     * 注册观察者，首次注册时立即通知当前状态。
     */
    void addObserver(String packageName, IObserver observer, boolean editModeEnabled,
            ActRules currentRules) {
        synchronized (mRemoteCallbackList) {
            synchronized (mRegisteredObserverMap) {
                IBinder binder = observer.asBinder();
                if (mRegisteredObserverMap.containsKey(packageName)
                        && mRegisteredObserverMap.get(packageName) == binder) {
                    mLogger.d("observer already registered for: " + packageName);
                    return;
                }
                mRegisteredObserverMap.put(packageName, binder);
            }
            mRemoteCallbackList.register(new ObserverProxy(packageName, observer));
            scheduleDeadObserverCleanup();
        }
        // 立即通知新注册的观察者当前状态
        try {
            observer.onEditModeChanged(editModeEnabled);
            observer.onViewRuleChanged(packageName, currentRules);
        } catch (RemoteException e) {
            mLogger.w("immediate notify observer failed", e);
        }
    }

    /** 注销观察者 */
    void removeObserver(String packageName, IObserver observer) {
        synchronized (mRemoteCallbackList) {
            mRemoteCallbackList.unregister(new ObserverProxy(packageName, observer));
            synchronized (mRegisteredObserverMap) {
                mRegisteredObserverMap.remove(packageName);
            }
        }
    }

    // ---- 事件通知 ----

    void notifyObserverRuleChanged(String packageName, ActRules actRules) {
        forEachLiveObserver((proxy) -> {
            if (TextUtils.equals(proxy.packageName, packageName)
                    || TextUtils.equals(proxy.packageName, "*")) {
                proxy.observer.onViewRuleChanged(packageName, actRules);
            }
        });
    }

    void notifyObserverEditModeChanged(boolean enable) {
        forEachLiveObserver((proxy) -> proxy.onEditModeChanged(enable));
    }

    // ---- 死观察者清理 ----

    void scheduleDeadObserverCleanup() {
        if (!mHandle.hasMessages(mCleanObserversMsgCode)) {
            mHandle.sendEmptyMessageDelayed(
                    mCleanObserversMsgCode, CLEAN_INTERVAL);
        }
    }

    void cleanDeadObservers() {
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
                            mRegisteredObserverMap.remove(proxy.packageName,
                                    proxy.observer.asBinder());
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
