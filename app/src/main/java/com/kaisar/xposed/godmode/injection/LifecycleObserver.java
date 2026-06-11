package com.kaisar.xposed.godmode.injection;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.event.Subscribe;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 监听 Activity 生命周期事件，管理 Activity 视图的规则应用/撤销。
 * <p>
 * 通过 EventBus 接收 {@link RulesChangedEvent} 实现规则动态更新。
 */
public final class LifecycleObserver extends XC_MethodHook {

    private static final String TAG = "LifecycleObserver";

    private final WeakHashMap<Activity, OnLayoutChangeListener> mActivities = new WeakHashMap<>();
    private final ActRules mActRules = new ActRules();
    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());
    private final java.util.Map<Activity, Runnable> mPendingReapply = new java.util.WeakHashMap<>();
    private boolean mRecyclerViewHooksInstalled;

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        super.afterHookedMethod(param);
        Activity activity = (Activity) param.thisObject;
        String methodName = param.method.getName();
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if ("onPostResume".equals(methodName)) {
            if (!mActivities.containsKey(activity)) {
                OnLayoutChangeListener listener = new OnLayoutChangeListener(activity);
                decorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                mActivities.put(activity, listener);
                decorView.post(listener::applyRuleIfMatchCondition);
                // 首次 onPostResume 时注册布局变化监听，将 Activity 存入 mActivities
                // 后续通过 applyRuleIfMatchCondition 在布局变化时重新应用规则
                scheduleRuleReapplication(activity);
            }
            installRecyclerViewHooks(activity);
            Logger.d(TAG, "[Lifecycle] resume: " + activity.getClass().getSimpleName() + " (total=" + mActivities.size() + ")");
        } else if ("onDestroy".equals(methodName)) {
            OnLayoutChangeListener listener = mActivities.remove(activity);
            decorView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            synchronized (mPendingReapply) {
                Runnable r = mPendingReapply.remove(activity);
                if (r != null) mDebounceHandler.removeCallbacks(r);
            }
            Logger.d(TAG, "[Lifecycle] destroy: " + activity.getClass().getSimpleName() + " (total=" + mActivities.size() + ")");
        }
    }

    /**
     * 接收规则变更事件，通过 EventBus 订阅。
     * 对比新旧规则差异，撤销旧规则并应用新规则，同时对所有存活 Activity 重新调度规则应用。
     */
    @SuppressWarnings("unchecked")
    @Subscribe
    public void onRulesChanged(RulesChangedEvent event) {
        ActRules newActRules = (ActRules) event.rules;
        if (newActRules == null) return;
        // 先保存旧规则用于对比差异（mActRules 缓存当前已应用的规则）
        // 旧规则由 IPC addObserver 提前推送到此，在 onPostResume 时通过 applyRuleBatch 应用
        if (newActRules.equals(mActRules)) return;
        ViewController.getDefault().clearBlockedCache();
        Set<Map.Entry<String, List<RuleRecord>>> entries = newActRules.entrySet();
        for (Map.Entry<String, List<RuleRecord>> entry : entries) {
            String key = entry.getKey();
            List<RuleRecord> oldRules = mActRules.get(key);
            List<RuleRecord> newRules = entry.getValue();
            if (newRules != null && oldRules != null) {
                oldRules.removeAll(newRules);
                if (oldRules.isEmpty()) mActRules.remove(key);
            }
        }
        // revoke old rules
        if (!mActRules.isEmpty()) {
            entries = mActRules.entrySet();
            for (Map.Entry<String, List<RuleRecord>> entry : entries) {
                List<RuleRecord> rules = entry.getValue();
                if (rules == null || rules.isEmpty()) continue;
                List<RuleRecord> revRemove = new java.util.ArrayList<>();
                List<RuleRecord> revModify = new java.util.ArrayList<>();
                for (RuleRecord r : rules) {
                    if (r.isModifyRule()) revModify.add(r);
                    else revRemove.add(r);
                }
                for (Activity activity : mActivities.keySet()) {
                    if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                        if (!revRemove.isEmpty()) ViewController.getDefault().revokeRuleBatch(activity, revRemove);
                        if (!revModify.isEmpty()) ViewController.getDefault().revokeRuleBatch(activity, revModify);
                    }
                }
            }
        }
        // apply new rules
        mActRules.clear();
        mActRules.putAll(newActRules);
        entries = mActRules.entrySet();
        for (Map.Entry<String, List<RuleRecord>> entry : entries) {
            List<RuleRecord> rules = entry.getValue();
            for (Activity activity : mActivities.keySet()) {
                if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                    if (!rules.isEmpty()) {
                        ViewController.getDefault().applyRuleBatch(activity, rules);
                    }
                }
            }
        }
        // 注意：applyRuleBatch 对每个 Activity 应用规则，但不保证立即生效
        // 对于动态内容（如 RecyclerView、Fragment），布局可能在应用后发生变化
        // 因此通过 onGlobalLayout 监听 UI 布局变化后重新调度规则
        // scheduleRuleReapplication 使用 200ms 防抖延迟，避免频繁重新应用
        for (Activity activity : mActivities.keySet()) {
            scheduleRuleReapplication(activity);
        }
    }

    private void scheduleRuleReapplication(final Activity activity) {
        synchronized (mPendingReapply) {
            Runnable existing = mPendingReapply.get(activity);
            if (existing != null) mDebounceHandler.removeCallbacks(existing);
            Runnable r = () -> {
                synchronized (mPendingReapply) { mPendingReapply.remove(activity); }
                // 防抖结束后重新应用规则（布局稳定后的最终状态）
                OnLayoutChangeListener listener = mActivities.get(activity);
                if (listener != null) listener.applyRuleIfMatchCondition();
            };
            mPendingReapply.put(activity, r);
            mDebounceHandler.postDelayed(r, 200);
        }
    }

    private void installRecyclerViewHooks(Activity activity) {
        if (mRecyclerViewHooksInstalled) return;
        try {
            Class<?> adapterClass = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$Adapter", activity.getClassLoader());
            XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (mActRules.isEmpty()) return; // 没有活跃规则时跳过
                    for (Activity act : mActivities.keySet()) {
                        if (act != null && !act.isFinishing()) scheduleRuleReapplication(act);
                    }
                }
            });
            mRecyclerViewHooksInstalled = true;
            Logger.i(TAG, "[Lifecycle] DynamicContent: RecyclerView adapter hook installed");
        } catch (Throwable t) {
            Logger.d(TAG, "[Lifecycle] DynamicContent: RecyclerView hook skipped: " + t.getMessage());
        }
    }

    final class OnLayoutChangeListener implements ViewTreeObserver.OnGlobalLayoutListener {

        final WeakReference<Activity> activityReference;
        private volatile boolean mApplying; // 防止重复应用

        OnLayoutChangeListener(Activity activity) {
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalLayout() {
            if (mApplying) return; // 正在应用规则中，跳过本次布局回调
            applyRuleIfMatchCondition();
        }

        void applyRuleIfMatchCondition() {
            if (mApplying) return;
            mApplying = true;
            try {
                Activity activity = Preconditions.checkNotNull(activityReference.get());
                List<RuleRecord> rules = mActRules.get(activity.getComponentName().getClassName());
                if (rules != null && !rules.isEmpty()) {
                    // 异步匹配 + 应用，完成后重置 mApplying 防止重复进入
                    ViewController.getDefault().applyRuleBatch(activity, rules, () -> mApplying = false);
                } else {
                    mApplying = false;
                }
            } catch (Exception e) {
                Logger.w(TAG, "[Lifecycle] OnLayoutChange: applyRuleIfMatchCondition failed: " + e.getMessage());
                mApplying = false;
            }
        }

    }

}
