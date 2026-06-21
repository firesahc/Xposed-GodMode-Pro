package com.kaisar.xposed.godmode.injection;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.event.Subscribe;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        if ("onPostResume".equals(methodName)) {
            ViewGroup decorView = getDecorView(activity);
            if (decorView == null) return;
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
            removeLayoutListener(activity, listener);
            synchronized (mPendingReapply) {
                Runnable r = mPendingReapply.remove(activity);
                if (r != null) mDebounceHandler.removeCallbacks(r);
            }
            Logger.d(TAG, "[Lifecycle] destroy: " + activity.getClass().getSimpleName() + " (total=" + mActivities.size() + ")");
        }
    }

    private static ViewGroup getDecorView(Activity activity) {
        if (activity == null || activity.getWindow() == null) return null;
        try {
            return (ViewGroup) activity.getWindow().getDecorView();
        } catch (Exception e) {
            Logger.w(TAG, "[Lifecycle] getDecorView failed: " + e.getMessage());
            return null;
        }
    }

    private static void removeLayoutListener(Activity activity, OnLayoutChangeListener listener) {
        if (listener == null) return;
        ViewGroup decorView = getDecorView(activity);
        if (decorView == null) return;
        ViewTreeObserver observer = decorView.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(listener);
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
        if (newActRules.equals(mActRules)) return;
        ViewController.getDefault().clearBlockedCache();

        // Step 1: 基于旧规则快照计算差异（需要撤销的规则 = 旧规则中不在新规则内的部分）
        // 克隆旧规则快照以避免在原地修改 mActRules 时产生并发修改问题
        ActRules oldRulesSnapshot = new ActRules();
        for (Map.Entry<String, List<RuleRecord>> oldEntry : mActRules.entrySet()) {
            List<RuleRecord> newList = newActRules.get(oldEntry.getKey());
            if (newList == null) {
                // 该 Activity 的新规则完全消失，撤销全部旧规则
                oldRulesSnapshot.put(oldEntry.getKey(), new ArrayList<>(oldEntry.getValue()));
            } else {
                // 计算差集：旧规则中存在但新规则中不存在的规则需要撤销
                List<RuleRecord> diff = new ArrayList<>(oldEntry.getValue());
                diff.removeAll(newList);
                if (!diff.isEmpty()) {
                    oldRulesSnapshot.put(oldEntry.getKey(), diff);
                }
            }
        }

        // Step 2: 撤销旧规则（只撤销 diff 部分）
        for (Map.Entry<String, List<RuleRecord>> entry : oldRulesSnapshot.entrySet()) {
            List<RuleRecord> rules = entry.getValue();
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

        // Step 3: 更新缓存的规则集
        mActRules.clear();
        mActRules.putAll(newActRules);

        // Step 4: 应用新规则
        for (Map.Entry<String, List<RuleRecord>> entry : mActRules.entrySet()) {
            List<RuleRecord> rules = entry.getValue();
            for (Activity activity : mActivities.keySet()) {
                if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                    if (!rules.isEmpty()) {
                        ViewController.getDefault().applyRuleBatch(activity, rules);
                    }
                }
            }
        }

        // 仅对有规则的 Activity 调度重应用，避免对所有存活 Activity 无谓排程
        for (Map.Entry<String, List<RuleRecord>> entry : mActRules.entrySet()) {
            for (Activity activity : mActivities.keySet()) {
                if (TextUtils.equals(activity.getComponentName().getClassName(), entry.getKey())) {
                    scheduleRuleReapplication(activity);
                }
            }
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
            mDebounceHandler.post(r);
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
                    for (Map.Entry<String, List<RuleRecord>> entry : mActRules.entrySet()) {
                        for (Activity act : mActivities.keySet()) {
                            if (act != null && !act.isFinishing()
                                    && TextUtils.equals(act.getComponentName().getClassName(), entry.getKey())) {
                                scheduleRuleReapplication(act);
                            }
                        }
                    }
                }
            });
            Class<?> viewHolderClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$ViewHolder",
                    activity.getClassLoader());
            XposedHelpers.findAndHookMethod(adapterClass, "bindViewHolder",
                    viewHolderClass, int.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (mActRules.isEmpty()) return;
                            Object holder = param.args[0];
                            if (holder == null) return;
                            View itemView = (View) XposedHelpers.getObjectField(holder, "itemView");
                            applyRepeatableRulesToBoundItem(itemView);
                        }
                    });
            mRecyclerViewHooksInstalled = true;
            Logger.i(TAG, "[Lifecycle] DynamicContent: RecyclerView adapter hook installed");
        } catch (Throwable t) {
            Logger.d(TAG, "[Lifecycle] DynamicContent: RecyclerView hook skipped: " + t.getMessage());
        }
    }

    private void applyRepeatableRulesToBoundItem(View itemRoot) {
        if (itemRoot == null || itemRoot.getVisibility() != View.VISIBLE) return;
        Activity activity = ViewUtils.getAttachedActivityFromView(itemRoot);
        if (activity == null || activity.isFinishing()) return;
        List<RuleRecord> rules = mActRules.get(activity.getComponentName().getClassName());
        if (rules == null || rules.isEmpty()) return;

        OnLayoutChangeListener listener = mActivities.get(activity);

        for (RuleRecord rule : rules) {
            if (!rule.isRepeatable()) continue;
            try {
                MatchSpec spec = RuleMapper.toEngine(rule).getMatchSpec();
                if (spec.itemPath == null || spec.itemPath.length == 0
                        || spec.itemRootClass == null
                        || !itemRoot.getClass().getName().equals(spec.itemRootClass)) {
                    continue;
                }

                if (spec.targetLevel == TargetLevel.CARD) {
                    // CARD 模式：将卡片根传给 applyRule，由 resolveCardTarget
                    // 负责唯一的 itemPath 导航，避免在此处重复导航。
                    ViewController.getDefault().applyRule(itemRoot, rule);
                    if (listener != null) listener.mSelfTriggeredLayout = true;
                    continue;
                }

                // ELEMENT 模式：在此处完成 itemPath 导航 + 结构验证，
                // 然后直接将已验证的内部元素传给 applyRule。
                View matched = ViewTraversal.findViewByItemPath(itemRoot, spec.itemPath, 0);
                if (matched == null) {
                    matched = ViewTraversal.findViewByClassChain(itemRoot, spec.itemPath, 0);
                }
                if (matched != null && CompositeMatcher.isStructuralMatch(matched, spec, false)) {
                    ViewController.getDefault().applyRule(matched, rule);
                    if (listener != null) listener.mSelfTriggeredLayout = true;
                }
            } catch (Throwable t) {
                Logger.w(TAG, "[Lifecycle] apply bound item rule failed", t);
            }
        }
    }

    final class OnLayoutChangeListener implements ViewTreeObserver.OnGlobalLayoutListener {

        final WeakReference<Activity> activityReference;
        private volatile boolean mApplying; // 同步守卫：同一调用链内防止重入
        volatile boolean mSelfTriggeredLayout; // 异步守卫：跳过任何规则应用自身触发的 onGlobalLayout

        OnLayoutChangeListener(Activity activity) {
            activityReference = new WeakReference<>(activity);
        }

        @Override
        public void onGlobalLayout() {
            if (mApplying) return;
            if (mSelfTriggeredLayout) {
                // 此布局由规则应用（bindViewHolder 或 applyRuleBatch）自身触发——
                // 规则已在应用阶段精确处理，无需再执行全树扫描。
                mSelfTriggeredLayout = false;
                return;
            }
            applyRuleIfMatchCondition();
        }

        void applyRuleIfMatchCondition() {
            if (mApplying) return;
            mApplying = true;
            // 标记下一次 onGlobalLayout 为"自身触发"——applyRuleBatch 内部修改 View
            // 属性后将触发 requestLayout()，对应的 onGlobalLayout 回调应被跳过，
            // 避免无意义的全树扫描反馈循环。
            mSelfTriggeredLayout = true;
            try {
                Activity activity = Preconditions.checkNotNull(activityReference.get());
                List<RuleRecord> rules = mActRules.get(activity.getComponentName().getClassName());
                if (rules != null && !rules.isEmpty()) {
                    ViewController.getDefault().applyRuleBatch(activity, rules,
                            () -> mApplying = false);
                } else {
                    mApplying = false;
                    mSelfTriggeredLayout = false;
                }
            } catch (Exception e) {
                Logger.w(TAG, "[Lifecycle] OnLayoutChange: applyRuleIfMatchCondition failed: " + e.getMessage());
                mApplying = false;
                mSelfTriggeredLayout = false;
            }
        }

    }

}
