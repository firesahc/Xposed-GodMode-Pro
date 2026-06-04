package com.kaisar.xposed.godmode.injection.hook;

import android.app.Activity;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 动态内容观察器 — 布局监听 + RecyclerView Hook。
 * 从 ActivityLifecycleHook 提取的独立职责。
 */
final class DynamicContentObserver {

    private static final String TAG = "GodMode";
    private static boolean sRecyclerViewHooksInstalled;

    private DynamicContentObserver() {}

    /**
     * 为 Activity 注册全局布局监听器和延迟重试。
     */
    static void observe(Activity activity, ViewGroup decorView,
            OnLayoutChanged callback, ActRules actRules) {
        OnGlobalListener listener = new OnGlobalListener(activity);
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        decorView.post(() -> {
            if (actRules != null) callback.onApplyRules(activity, actRules);
        });
    }

    /**
     * 安装 RecyclerView 适配器变更 Hook（全局，仅一次）。
     */
    static void installRecyclerViewHooks(Activity activity,
            java.util.function.Consumer<Activity> onAdapterChanged) {
        if (sRecyclerViewHooksInstalled) return;
        try {
            Class<?> adapterClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter",
                    activity.getClassLoader());
            XposedHelpers.findAndHookMethod(adapterClass,
                    "notifyDataSetChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onAdapterChanged.accept(null);
                }
            });
            sRecyclerViewHooksInstalled = true;
            Logger.i(TAG, "[DynamicContent] RecyclerView adapter hook installed");
        } catch (Throwable t) {
            Logger.d(TAG, "[DynamicContent] RecyclerView hook skipped: " + t.getMessage());
        }
    }

    // ---- 接口 ----

    interface OnLayoutChanged {
        void onApplyRules(Activity activity, ActRules actRules);
    }

    // ---- 内部监听器 ----

    static final class OnGlobalListener implements ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<Activity> activityRef;
        volatile boolean mApplying;

        OnGlobalListener(Activity activity) {
            activityRef = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalLayout() {
            // 由 ActivityLifecycleHook 的 sActivities 统一管理，此处不做处理
        }
    }
}
