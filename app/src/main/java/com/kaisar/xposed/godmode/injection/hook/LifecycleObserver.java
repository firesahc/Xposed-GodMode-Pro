package com.kaisar.xposed.godmode.injection.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.event.Subscribe;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 监听 Activity 生命周期，在 Activity 恢复/销毁时应用/撤销规则。
 * <p>
 * 通过 EventBus 订阅 {@link RulesChangedEvent} 接收规则变更通知。
 */
public final class LifecycleObserver extends XC_MethodHook {

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
                // 确保在规则已到达但 mActivities 尚为空（规则早于 onPostResume）
                // 或视图在初次 applyRuleIfMatchCondition 时尚未就绪时有一个延迟重试。
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
     * 接收规则变更通知（EventBus 路径）。
     * 撤销旧规则，应用新规则，然后为所有已跟踪 Activity 调度延迟重试。
     */
    @SuppressWarnings("unchecked")
    @Subscribe
    public void onRulesChanged(RulesChangedEvent event) {
        ActRules newActRules = (ActRules) event.rules;
        if (newActRules == null) return;
        // 规则未变化时跳过，避免不必要的撤销→再应用导致的闪回
        // 触发场景：IPC addObserver 推送的规则与 onPostResume 中已应用的规则完全相同时
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
        // 修复: 在规则存放后为所有已跟踪 Activity 调度重应用，
        // 以处理视图在规则首次到达时尚不存在的情况（例如异步填充、Fragment 懒加载）。
        // 如果视图尚不可用，applyRuleBatch 会静默失败，
        // 而 onGlobalLayout 在静态 UI 上可能永远不会再次触发。
        // scheduleRuleReapplication（200ms 消抖）提供重试窗口以捕获动态创建的视图。
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
                // 不清理缓存：重应用应增量补充未覆盖的规则，而非破坏已生效的修改
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
                    if (mActRules.isEmpty()) return; // 无规则时不触发重匹配
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
        private volatile boolean mApplying; // 防重入标志

        OnLayoutChangeListener(Activity activity) {
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalLayout() {
            if (mApplying) return; // 防止规则应用触发的布局变更导致递归重入
            applyRuleIfMatchCondition();
        }

        void applyRuleIfMatchCondition() {
            if (mApplying) return;
            mApplying = true;
            try {
                Activity activity = Preconditions.checkNotNull(activityReference.get());
                List<RuleRecord> rules = mActRules.get(activity.getComponentName().getClassName());
                if (rules != null && !rules.isEmpty()) {
                    if (!rules.isEmpty()) {
                        ViewController.getDefault().applyRuleBatch(activity, rules);
                    }
                }
            } catch (Exception e) {
                Logger.w(TAG, "[Lifecycle] OnLayoutChange: applyRuleIfMatchCondition failed: " + e.getMessage());
            } finally {
                mApplying = false;
            }
        }

    }

}
