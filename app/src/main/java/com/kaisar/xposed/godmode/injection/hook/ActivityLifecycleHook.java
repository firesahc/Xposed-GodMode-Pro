package com.kaisar.xposed.godmode.injection.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.injection.RuleModificationHelper;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.Property;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class ActivityLifecycleHook extends XC_MethodHook implements Property.OnPropertyChangeListener<ActRules> {

    private static final WeakHashMap<Activity, OnLayoutChangeListener> sActivities = new WeakHashMap<>();
    private static final ActRules sActRules = new ActRules();
    private static final Handler sDebounceHandler = new Handler(Looper.getMainLooper());
    private static final java.util.Map<Activity, Runnable> sPendingReapply = new java.util.WeakHashMap<>();
    private static boolean sRecyclerViewHooksInstalled;

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        super.afterHookedMethod(param);
        Activity activity = (Activity) param.thisObject;
        String methodName = param.method.getName();
        ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
        if ("onPostResume".equals(methodName)) {
            if (!sActivities.containsKey(activity)) {
                OnLayoutChangeListener listener = new OnLayoutChangeListener(activity);
                decorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                sActivities.put(activity, listener);
                decorView.post(listener::applyRuleIfMatchCondition);
                // 确保在规则已到达但 sActivities 尚为空（规则早于 onPostResume）
                // 或视图在初次 applyRuleIfMatchCondition 时尚未就绪时有一个延迟重试。
                // 如果 onPropertyChange 中新规则的循环没有触发（sActivities 为空），
                // 此处的 scheduleRuleReapplication 会在规则添加后提供唯一的重试窗口。
                scheduleRuleReapplication(activity);
            }
            installRecyclerViewHooks(activity);
            Logger.d(TAG, "[ActivityLifecycle] resume: " + activity.getClass().getSimpleName() + " (total=" + sActivities.size() + ")");
        } else if ("onDestroy".equals(methodName)) {
            OnLayoutChangeListener listener = sActivities.remove(activity);
            decorView.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
            synchronized (sPendingReapply) {
                Runnable r = sPendingReapply.remove(activity);
                if (r != null) sDebounceHandler.removeCallbacks(r);
            }
            Logger.d(TAG, "[ActivityLifecycle] destroy: " + activity.getClass().getSimpleName() + " (total=" + sActivities.size() + ")");
        }
    }

    @Override
    public void onPropertyChange(ActRules newActRules) {
        if (newActRules == null) return;
        // 规则未变化时跳过，避免不必要的撤销→再应用导致的闪回
        // 触发场景：IPC addObserver 推送的规则与 onPostResume 中已应用的规则完全相同时
        if (newActRules.equals(sActRules)) return;
        RuleModificationHelper.clearAppliedCache();
        Set<Map.Entry<String, List<ViewRule>>> entries = newActRules.entrySet();
        for (Map.Entry<String, List<ViewRule>> entry : entries) {
            String key = entry.getKey();
            List<ViewRule> oldRules = sActRules.get(key);
            List<ViewRule> newRules = entry.getValue();
            if (newRules != null && oldRules != null) {
                oldRules.removeAll(newRules);
                if (oldRules.isEmpty()) sActRules.remove(key);
            }
        }
        // revoke old rules
        if (!sActRules.isEmpty()) {
            entries = sActRules.entrySet();
            for (Map.Entry<String, List<ViewRule>> entry : entries) {
                List<ViewRule> rules = entry.getValue();
                if (rules == null || rules.isEmpty()) continue;
                List<ViewRule> revRemove = new java.util.ArrayList<>();
                List<ViewRule> revModify = new java.util.ArrayList<>();
                for (ViewRule r : rules) {
                    if (r.isModifyRule()) revModify.add(r);
                    else revRemove.add(r);
                }
                for (Activity activity : sActivities.keySet()) {
                    if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                        if (!revRemove.isEmpty()) ViewController.revokeRuleBatch(activity, revRemove);
                        for (ViewRule r : revModify) RuleModificationHelper.revokeModificationRule(activity, r);
                    }
                }
            }
        }
        // apply new rules
        sActRules.clear();
        sActRules.putAll(newActRules);
        entries = sActRules.entrySet();
        for (Map.Entry<String, List<ViewRule>> entry : entries) {
            List<ViewRule> rules = entry.getValue();
            for (Activity activity : sActivities.keySet()) {
                if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                    for (ViewRule rule : rules) {
                        if (rule.isModifyRule()) {
                            RuleModificationHelper.applyModificationRule(activity, rule);
                        }
                    }
                    List<ViewRule> removeRules = new java.util.ArrayList<>();
                    for (ViewRule r : rules) {
                        if (r.isRemoveRule()) removeRules.add(r);
                    }
                    if (!removeRules.isEmpty()) {
                        ViewController.applyRuleBatch(activity, removeRules);
                    }
                }
            }
        }
        // 修复: 在规则存放后为所有已跟踪 Activity 调度重应用，
        // 以处理视图在规则首次到达时尚不存在的情况（例如异步填充、Fragment 懒加载）。
        // 如果视图尚不可用，applyRuleBatch 会静默失败，
        // 而 onGlobalLayout 在静态 UI 上可能永远不会再次触发。
        // scheduleRuleReapplication（200ms 消抖）提供重试窗口以捕获动态创建的视图。
        for (Activity activity : sActivities.keySet()) {
            scheduleRuleReapplication(activity);
        }
    }

    private static void scheduleRuleReapplication(final Activity activity) {
        synchronized (sPendingReapply) {
            Runnable existing = sPendingReapply.get(activity);
            if (existing != null) sDebounceHandler.removeCallbacks(existing);
            Runnable r = () -> {
                synchronized (sPendingReapply) { sPendingReapply.remove(activity); }
                // 不清理缓存：重应用应增量补充未覆盖的规则，而非破坏已生效的修改
                OnLayoutChangeListener listener = sActivities.get(activity);
                if (listener != null) listener.applyRuleIfMatchCondition();
            };
            sPendingReapply.put(activity, r);
            sDebounceHandler.postDelayed(r, 200);
        }
    }

    private static void installRecyclerViewHooks(Activity activity) {
        if (sRecyclerViewHooksInstalled) return;
        try {
            Class<?> adapterClass = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView$Adapter", activity.getClassLoader());
            XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    for (Activity act : sActivities.keySet()) {
                        if (act != null && !act.isFinishing()) scheduleRuleReapplication(act);
                    }
                }
            });
            sRecyclerViewHooksInstalled = true;
            Logger.i(TAG, "[ActivityLifecycle] DynamicContent: RecyclerView adapter hook installed");
        } catch (Throwable t) {
            Logger.d(TAG, "[ActivityLifecycle] DynamicContent: RecyclerView hook skipped: " + t.getMessage());
        }
    }

    static final class OnLayoutChangeListener implements ViewTreeObserver.OnGlobalLayoutListener {

        final WeakReference<Activity> activityReference;

        OnLayoutChangeListener(Activity activity) {
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalLayout() {
            applyRuleIfMatchCondition();
        }

        void applyRuleIfMatchCondition() {
            try {
                Activity activity = Preconditions.checkNotNull(activityReference.get());
                List<ViewRule> rules = sActRules.get(activity.getComponentName().getClassName());
                if (rules != null && !rules.isEmpty()) {
                    List<ViewRule> removeRules = new java.util.ArrayList<>();
                    for (ViewRule rule : rules) {
                        if (rule.isRemoveRule()) removeRules.add(rule);
                        else if (rule.isModifyRule()) RuleModificationHelper.applyModificationRule(activity, rule);
                    }
                    if (!removeRules.isEmpty()) {
                        ViewController.applyRuleBatch(activity, removeRules);
                    }
                }
            } catch (Exception e) {
                Logger.w(TAG, "[ActivityLifecycle] OnLayoutChange: applyRuleIfMatchCondition failed: " + e.getMessage());
            }
        }

    }

}
