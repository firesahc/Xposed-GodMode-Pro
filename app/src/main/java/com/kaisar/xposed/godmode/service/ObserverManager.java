package com.kaisar.xposed.godmode.service;

import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 瑙傚療鑰呯鐞嗗櫒 鈥?RemoteCallbackList 绠＄悊 + 姝昏瀵熻€呮竻鐞?+ 閫氱煡骞挎挱銆?
 * 浠?GodModeManagerService 鎻愬彇鐨勭嫭绔嬭亴璐ｃ€?
 */
final class ObserverManager {

    /** 姝昏瀵熻€呮竻鐞嗛棿闅?(ms) */
    static final long CLEAN_INTERVAL = 60_000L;

    private final RemoteCallbackList<ObserverProxy> mRemoteCallbackList = new RemoteCallbackList<>();
    private final HashMap<String, IBinder> mRegisteredObserverMap = new HashMap<>();
    private final Logger mLogger;
    private final Handler mHandle;
    private final int mCleanObserversMsgCode;

    /**
     * @param logger               鏃ュ織璁板綍鍣?
     * @param handle               Handler 鐢ㄤ簬璋冨害娓呯悊娑堟伅
     * @param cleanObserversMsgCode Handler 娑堟伅浠ｇ爜锛岀敱璋冪敤鏂逛紶鍏ワ紙瑙ｈ€﹀ GodModeManagerService 鐨勪緷璧栵級
     */
    ObserverManager(Logger logger, Handler handle, int cleanObserversMsgCode) {
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCleanObserversMsgCode = cleanObserversMsgCode;
    }

    // ---- 瑙傚療鑰呮敞鍐?娉ㄩ攢 ----

    /**
     * 娉ㄥ唽瑙傚療鑰呭苟绔嬪嵆鎺ㄩ€佸綋鍓嶇紪杈戞ā寮忓拰瑙勫垯鐘舵€併€?
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
        // 绔嬪嵆鎺ㄩ€佸綋鍓嶇姸鎬?
        try {
            observer.onEditModeChanged(editModeEnabled);
            observer.onViewRuleChanged(packageName, currentRules);
        } catch (RemoteException e) {
            mLogger.w("immediate notify observer failed", e);
        }
    }

    /** 娉ㄩ攢瑙傚療鑰?*/
    void removeObserver(String packageName, IObserver observer) {
        synchronized (mRemoteCallbackList) {
            mRemoteCallbackList.unregister(new ObserverProxy(packageName, observer));
            synchronized (mRegisteredObserverMap) {
                mRegisteredObserverMap.remove(packageName);
            }
        }
    }

    // ---- 閫氱煡骞挎挱 ----

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

    // ---- 姝昏瀵熻€呮竻鐞?----

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

    // ---- 鍐呴儴宸ュ叿 ----

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
