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
 * 鐟欏倸鐧傞懓鍛吀閻炲棗娅?閳?RemoteCallbackList 缁狅紕鎮?+ 濮濇槒顫囩€电喕鈧懏绔婚悶?+ 闁氨鐓￠獮鎸庢尡閵?
 * 娴?GodModeManagerService 閹绘劕褰囬惃鍕缁斿浜寸拹锝冣偓?
 */
final class ObserverManager {

    /** 濮濇槒顫囩€电喕鈧懏绔婚悶鍡涙？闂?(ms) */
    static final long CLEAN_INTERVAL = 60_000L;

    private final RemoteCallbackList<ObserverProxy> mRemoteCallbackList = new RemoteCallbackList<>();
    private final HashMap<String, IBinder> mRegisteredObserverMap = new HashMap<>();
    private final Logger mLogger;
    private final Handler mHandle;
    private final int mCleanObserversMsgCode;

    /**
     * @param logger               閺冦儱绻旂拋鏉跨秿閸?
     * @param handle               Handler 閻劋绨拫鍐ㄥ濞撳懐鎮婂☉鍫熶紖
     * @param cleanObserversMsgCode Handler 濞戝牊浼呮禒锝囩垳閿涘瞼鏁辩拫鍐暏閺傞€涚炊閸忋儻绱欑憴锝堚偓锕€顕?GodModeManagerService 閻ㄥ嫪绶风挧鏍电礆
     */
    ObserverManager(Logger logger, Handler handle, int cleanObserversMsgCode) {
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCleanObserversMsgCode = cleanObserversMsgCode;
    }

    // ---- 鐟欏倸鐧傞懓鍛暈閸?濞夈劑鏀?----

    /**
     * 濞夈劌鍞界憴鍌氱檪閼板懎鑻熺粩瀣祮閹恒劑鈧礁缍嬮崜宥囩椽鏉堟垶膩瀵繐鎷扮憴鍕灟閻樿埖鈧降鈧?
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
        // 缁斿宓嗛幒銊┾偓浣哥秼閸撳秶濮搁幀?
        try {
            observer.onEditModeChanged(editModeEnabled);
            observer.onViewRuleChanged(packageName, currentRules);
        } catch (RemoteException e) {
            mLogger.w("immediate notify observer failed", e);
        }
    }

    /** 濞夈劑鏀㈢憴鍌氱檪閼?*/
    void removeObserver(String packageName, IObserver observer) {
        synchronized (mRemoteCallbackList) {
            mRemoteCallbackList.unregister(new ObserverProxy(packageName, observer));
            synchronized (mRegisteredObserverMap) {
                mRegisteredObserverMap.remove(packageName);
            }
        }
    }

    // ---- 闁氨鐓￠獮鎸庢尡 ----

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

    // ---- 濮濇槒顫囩€电喕鈧懏绔婚悶?----

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

    // ---- 閸愬懘鍎村銉ュ徔 ----

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
