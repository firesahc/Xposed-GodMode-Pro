package com.kaisar.xposed.godmode.injection.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

/**
 * 规则重应用调度器 — 200ms 消抖延迟重试。
 * 从 ActivityLifecycleHook 提取的独立职责。
 */
final class RuleReapplyScheduler {

    private static final long DEBOUNCE_MS = 200;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final java.util.Map<Activity, Runnable> sPending =
            new java.util.WeakHashMap<>();

    private RuleReapplyScheduler() {}

    /** 为指定 Activity 调度规则重应用。重复调用会重置计时器。 */
    static void schedule(Activity activity, Runnable action) {
        synchronized (sPending) {
            Runnable existing = sPending.get(activity);
            if (existing != null) sHandler.removeCallbacks(existing);
            Runnable r = () -> {
                synchronized (sPending) { sPending.remove(activity); }
                action.run();
            };
            sPending.put(activity, r);
            sHandler.postDelayed(r, DEBOUNCE_MS);
        }
    }

    /** 取消指定 Activity 的待处理重应用 */
    static void cancel(Activity activity) {
        synchronized (sPending) {
            Runnable r = sPending.remove(activity);
            if (r != null) sHandler.removeCallbacks(r);
        }
    }
}
