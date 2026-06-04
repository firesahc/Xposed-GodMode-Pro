package com.kaisar.xposed.godmode.service;

import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 观察者管理器 — RemoteCallbackList 管理 + 死观察者清理 + 通知广播。
 * 从 GodModeManagerService 提取的独立职责。
 */
final class ObserverManager {

    /** 死观察者清理间隔 (ms) */
    static final long CLEAN_INTERVAL = 60_000L;

    private final RemoteCallbackList<ObserverProxy> mRemoteCallbackList = new RemoteCallbackList<>();
    private final HashMap<String, IBinder> mRegisteredObserverMap = new HashMap<>();
    private final Logger mLogger;
    private final Handler mHandle;

    ObserverManager(Logger logger, Handler handle) {
        this.mLogger = logger;
        this.mHandle = handle;
    }

    // ---- 观察者注册/注销 ----

    /**
     * 注册观察者并立即推送当前编辑模式和规则状态。
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
        // 立即推送当前状态
        try {
            observer.onEditModeChanged(editModeEnabled);
            observer.onViewRuleChanged(packageName, rulesProvider.get());
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

    // ---- 通知广播 ----

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
        if (!mHandle.hasMessages(GodModeManagerService.CLEAN_OBSERVERS)) {
            mHandle.sendEmptyMessageDelayed(
                    GodModeManagerService.CLEAN_OBSERVERS, CLEAN_INTERVAL);
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

    // ---- 内部工具 ----

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
