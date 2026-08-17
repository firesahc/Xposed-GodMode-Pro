package com.kaisar.xposed.godmode.control;

import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.contract.IRuleObserver;

import java.util.ArrayList;
import java.util.List;

/** Tracks Binder observers and publishes only committed repository generations. */
public final class ObserverRegistry {
    private final Logger mLogger;
    private final RemoteCallbackList<IRuleObserver> mCallbacks =
            new RemoteCallbackList<IRuleObserver>() {
                @Override public void onCallbackDied(IRuleObserver observer, Object cookie) {
                    Subscription subscription = cookie instanceof Subscription
                            ? (Subscription) cookie : null;
                    mLogger.d("observer died: "
                            + (subscription == null ? "unknown" : subscription.packageName));
                }
            };

    public ObserverRegistry(Logger logger) {
        mLogger = logger;
    }

    /** Returns false when this Binder was already registered or has already died. */
    public boolean addObserver(String packageName, IRuleObserver observer) {
        return observer != null
                && mCallbacks.register(observer, new Subscription(packageName));
    }

    public void removeObserver(IRuleObserver observer) {
        if (observer != null) mCallbacks.unregister(observer);
    }

    public void notifyObserverRuleChanged(String packageName, long committedGeneration) {
        forEachLiveObserver((subscription, observer) -> {
            if (TextUtils.equals(subscription.packageName, packageName)
                    || TextUtils.equals(subscription.packageName, "*")) {
                observer.onRulesInvalidated(packageName, committedGeneration);
            }
        });
    }

    public void notifyObserverEditModeChanged(boolean enabled, long editRevision) {
        forEachLiveObserver((subscription, observer) ->
                observer.onEditModeChanged(enabled, editRevision));
    }

    /** Publishes the authoritative generation loaded by the repository. */
    public void notifyRulesLoaded(long committedGeneration) {
        forEachLiveObserver((subscription, observer) ->
                observer.onRulesInvalidated(subscription.packageName, committedGeneration));
    }

    public void shutdown() {
        mCallbacks.kill();
    }

    private void forEachLiveObserver(ObserverAction action) {
        List<Callback> callbacks = new ArrayList<>();
        int count = mCallbacks.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                IRuleObserver observer = mCallbacks.getBroadcastItem(index);
                Object cookie = mCallbacks.getBroadcastCookie(index);
                if (observer != null && cookie instanceof Subscription) {
                    callbacks.add(new Callback((Subscription) cookie, observer));
                }
            }
        } finally {
            mCallbacks.finishBroadcast();
        }
        // Binder calls are deliberately outside RemoteCallbackList's broadcast section.
        for (Callback callback : callbacks) {
            try {
                action.execute(callback.subscription, callback.observer);
            } catch (Exception e) {
                mLogger.w("notify observer failed", e);
            }
        }
    }

    private interface ObserverAction {
        void execute(Subscription subscription, IRuleObserver observer) throws RemoteException;
    }

    private static final class Callback {
        final Subscription subscription;
        final IRuleObserver observer;

        Callback(Subscription subscription, IRuleObserver observer) {
            this.subscription = subscription;
            this.observer = observer;
        }
    }

    private static final class Subscription {
        final String packageName;

        Subscription(String packageName) {
            this.packageName = packageName;
        }
    }
}
