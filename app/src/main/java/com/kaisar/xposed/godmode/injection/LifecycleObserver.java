package com.kaisar.xposed.godmode.injection;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 监听 Activity 生命周期事件，管理 Activity 视图的规则应用/撤销。
 * <p>
 * 通过 EventBus 接收 {@link RulesChangedEvent} 实现规则动态更新。
 * <p>
 * 规则应用有三条互补路径：
 * <ol>
 *   <li><b>bindViewHolder 精确应用</b> — 每个 RecyclerView item 绑定时立即匹配并应用，
 *       消除元素闪现时间窗口</li>
 *   <li><b>onGlobalLayout 全树扫描</b> — 布局稳定后批量匹配所有 repeatable 规则，
 *       通过 {@link OnLayoutChangeListener#mSelfTriggeredLayout} 跳过自身触发的回调</li>
 *   <li><b>防抖重应用</b> — {@code notifyDataSetChanged} 或规则变更后，
 *       50ms 延迟确保 RecyclerView 完成布局再执行全树扫描</li>
 * </ol>
 */
public final class LifecycleObserver extends XC_MethodHook {

    private static final String TAG = "LifecycleObserver";

    /** 防抖延迟：确保 RecyclerView 在 notifyDataSetChanged 后完成布局 */
    private static final long REAPPLY_DEBOUNCE_MS = 50L;

    private final WeakHashMap<Activity, OnLayoutChangeListener> mActivities = new WeakHashMap<>();
    private final ActRules mActRules = new ActRules();
    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());
    private final Map<Activity, Runnable> mPendingReapply = new WeakHashMap<>();
    private boolean mRecyclerViewHooksInstalled;

    // =========================================================================
    // XC_MethodHook — Activity 生命周期
    // =========================================================================

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        super.afterHookedMethod(param);
        Activity activity = (Activity) param.thisObject;
        String methodName = param.method.getName();
        if ("onPostResume".equals(methodName)) {
            onActivityResume(activity);
        } else if ("onDestroy".equals(methodName)) {
            onActivityDestroy(activity);
        }
    }

    private void onActivityResume(Activity activity) {
        ViewGroup decorView = getDecorView(activity);
        if (decorView == null) return;
        if (!mActivities.containsKey(activity)) {
            OnLayoutChangeListener listener = new OnLayoutChangeListener(activity);
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            mActivities.put(activity, listener);
            decorView.post(listener::applyRuleIfMatchCondition);
            scheduleRuleReapplication(activity);
        }
        installRecyclerViewHooks(activity);
    }

    private void onActivityDestroy(Activity activity) {
        OnLayoutChangeListener listener = mActivities.remove(activity);
        removeLayoutListener(activity, listener);
        synchronized (mPendingReapply) {
            Runnable r = mPendingReapply.remove(activity);
            if (r != null) mDebounceHandler.removeCallbacks(r);
        }
    }

    // =========================================================================
    // DecorView / LayoutListener 工具
    // =========================================================================

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

    // =========================================================================
    // 规则变更处理（EventBus 订阅）
    // =========================================================================

    /**
     * 接收规则变更事件，通过 EventBus 订阅。
     * <p>
     * 四步流程：
     * <ol>
     *   <li>计算差集 — 旧规则中不在新规则内的部分需要撤销</li>
     *   <li>撤销旧规则 — 必须在 clearBlockedCache 之前，revoke 依赖缓存中的 ViewProperty</li>
     *   <li>替换 + 应用新规则</li>
     *   <li>防抖重应用 — 50ms 延迟确保布局稳定</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    @Subscribe
    public void onRulesChanged(RulesChangedEvent event) {
        ActRules newRules = (ActRules) event.rules;
        if (newRules == null || newRules.equals(mActRules)) return;

        // Step 1: 撤销被删除/修改的旧规则（必须在 clearBlockedCache 之前）
        ActRules toRevoke = computeRuleDiff(mActRules, newRules);
        if (!toRevoke.isEmpty()) {
            revokeRulesForActivities(toRevoke);
        }

        // Step 2: 清除 RemoveApplier/ModifyApplier 缓存
        ViewController.getDefault().clearBlockedCache();

        // Step 3: 替换规则集并应用新规则
        mActRules.clear();
        mActRules.putAll(newRules);
        applyRulesForActivities(newRules);

        // Step 4: 防抖重应用（仅对受影响的 Activity）
        scheduleReapplyForActivities(newRules);
    }

    /**
     * 计算需要撤销的规则差集：旧规则中存在但新规则中不存在的部分。
     * <p>
     * 使用 {@link RuleRecord#equals(Object)} 窄匹配（仅比较定位身份字段），
     * 而非 contentEquals 宽匹配。这是有意为之——diff 旨在识别"已被删除的规则身份"。
     */
    private static ActRules computeRuleDiff(ActRules oldRules, ActRules newRules) {
        ActRules diff = new ActRules();
        for (Map.Entry<String, List<RuleRecord>> oldEntry : oldRules.entrySet()) {
            String className = oldEntry.getKey();
            List<RuleRecord> newList = newRules.get(className);
            if (newList == null) {
                diff.put(className, new ArrayList<>(oldEntry.getValue()));
            } else {
                List<RuleRecord> removed = new ArrayList<>(oldEntry.getValue());
                removed.removeAll(newList);
                if (!removed.isEmpty()) {
                    diff.put(className, removed);
                }
            }
        }
        return diff;
    }

    private void revokeRulesForActivities(ActRules toRevoke) {
        forEachMatchingActivity(toRevoke, (activity, rules) -> {
            List<RuleRecord> revRemove = new ArrayList<>();
            List<RuleRecord> revModify = new ArrayList<>();
            for (RuleRecord r : rules) {
                if (r.isModifyRule()) revModify.add(r);
                else revRemove.add(r);
            }
            if (!revRemove.isEmpty()) ViewController.getDefault().revokeRuleBatch(activity, revRemove);
            if (!revModify.isEmpty()) ViewController.getDefault().revokeRuleBatch(activity, revModify);
        });
    }

    private void applyRulesForActivities(ActRules rules) {
        forEachMatchingActivity(rules, (activity, ruleList) -> {
            if (!ruleList.isEmpty()) {
                ViewController.getDefault().applyRuleBatch(activity, ruleList);
            }
        });
    }

    private void scheduleReapplyForActivities(ActRules rules) {
        forEachMatchingActivity(rules, (activity, ruleList) ->
                scheduleRuleReapplication(activity));
    }

    // =========================================================================
    // Activity 安全迭代（WeakHashMap GC 防护）
    // =========================================================================

    /**
     * 安全遍历 {@link #mActivities}，自动跳过已 GC 或已 finishing 的 Activity。
     */
    private void forEachLiveActivity(Consumer<Activity> action) {
        for (Activity activity : mActivities.keySet()) {
            if (activity != null && !activity.isFinishing()) {
                action.accept(activity);
            }
        }
    }

    /**
     * 遍历规则集中的每个 Activity 类名，匹配对应的存活 Activity 后执行操作。
     * <p>
     * 内部复用 {@link #forEachLiveActivity}，保证 WeakHashMap 迭代安全。
     */
    private void forEachMatchingActivity(ActRules rules, BiConsumer<Activity, List<RuleRecord>> action) {
        for (Map.Entry<String, List<RuleRecord>> entry : rules.entrySet()) {
            String targetClass = entry.getKey();
            List<RuleRecord> ruleList = entry.getValue();
            forEachLiveActivity(activity -> {
                if (TextUtils.equals(activity.getComponentName().getClassName(), targetClass)) {
                    action.accept(activity, ruleList);
                }
            });
        }
    }

    // =========================================================================
    // 防抖重应用调度
    // =========================================================================

    private void scheduleRuleReapplication(final Activity activity) {
        synchronized (mPendingReapply) {
            Runnable existing = mPendingReapply.get(activity);
            if (existing != null) mDebounceHandler.removeCallbacks(existing);
            Runnable r = () -> {
                synchronized (mPendingReapply) { mPendingReapply.remove(activity); }
                OnLayoutChangeListener listener = mActivities.get(activity);
                if (listener != null) listener.applyRuleIfMatchCondition();
            };
            mPendingReapply.put(activity, r);
            mDebounceHandler.postDelayed(r, REAPPLY_DEBOUNCE_MS);
        }
    }

    // =========================================================================
    // RecyclerView Hook 管理
    // =========================================================================

    private void installRecyclerViewHooks(Activity activity) {
        if (mRecyclerViewHooksInstalled) return;
        try {
            Class<?> adapterClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter",
                    activity.getClassLoader());

            // Hook 1: notifyDataSetChanged → 清除 Applier 缓存 + 防抖重应用
            XposedHelpers.findAndHookMethod(adapterClass, "notifyDataSetChanged",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (mActRules.isEmpty()) return;
                            ViewController.getDefault().clearBlockedCache();
                            scheduleReapplyForActivities(mActRules);
                        }
                    });

            // Hook 2: bindViewHolder → 精确应用 repeatable 规则，消除闪烁
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
            Logger.i(TAG, "[Lifecycle] RecyclerView adapter hooks installed");
        } catch (Throwable t) {
            Logger.d(TAG, "[Lifecycle] RecyclerView hook skipped: " + t.getMessage());
        }
    }

    // =========================================================================
    // bindViewHolder 精确规则应用（快速路径）
    // =========================================================================

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
                if (!isApplicableToItem(spec, itemRoot)) continue;

                // CARD 和 ELEMENT 模式走统一的导航+验证管线，
                // 消除 730b660 引入的 CARD 分支不对称行为（盲传 itemRoot 无验证）。
                View target = navigateAndValidate(itemRoot, spec);
                if (target != null) {
                    ViewController.getDefault().applyRule(target, rule);
                    if (listener != null) listener.onRuleApplied();
                }
            } catch (Throwable t) {
                Logger.w(TAG, "[Lifecycle] apply bound item rule failed", t);
            }
        }
    }

    /**
     * 检查规则规格是否适用于当前 itemRoot。
     */
    private static boolean isApplicableToItem(MatchSpec spec, View itemRoot) {
        return spec.itemPath != null && spec.itemPath.length > 0
                && spec.itemRootClass != null
                && itemRoot.getClass().getName().equals(spec.itemRootClass);
    }

    /**
     * 通过 itemPath 导航到目标 View，并进行结构验证。
     * <p>
     * CARD 和 ELEMENT 模式使用完全相同的管线：
     * <ol>
     *   <li>精确索引 + 类名导航</li>
     *   <li>失败 → 纯类名链回退</li>
     *   <li>成功 → isStructuralMatch 验证</li>
     * </ol>
     *
     * @return 验证通过的目标 View，导航失败或验证失败返回 null
     */
    private static View navigateAndValidate(View itemRoot, MatchSpec spec) {
        View target = ViewTraversal.findViewByItemPath(itemRoot, spec.itemPath, 0);
        if (target == null) {
            target = ViewTraversal.findViewByClassChain(itemRoot, spec.itemPath, 0);
        }
        if (target != null && CompositeMatcher.isStructuralMatch(target, spec, false)) {
            return target;
        }
        return null;
    }

    // =========================================================================
    // OnLayoutChangeListener — 全局布局监听 + 自身触发跳过守卫
    // =========================================================================

    final class OnLayoutChangeListener implements ViewTreeObserver.OnGlobalLayoutListener {

        final WeakReference<Activity> activityReference;

        /** 同步守卫：同一调用链内防止重入 */
        private volatile boolean mApplying;

        /**
         * 异步守卫：规则应用修改 View 属性后触发 requestLayout → onGlobalLayout，
         * 设置此标志使下一次回调跳过，阻断跨帧反馈循环。
         * <p>
         * 由 {@link #onRuleApplied()} 设置，由 {@link #onGlobalLayout()} 检测并清除。
         */
        volatile boolean mSelfTriggeredLayout;

        OnLayoutChangeListener(Activity activity) {
            activityReference = new WeakReference<>(activity);
        }

        // ---- GlobalLayoutListener ----

        @Override
        public void onGlobalLayout() {
            if (mApplying) return;
            if (mSelfTriggeredLayout) {
                mSelfTriggeredLayout = false;
                return;
            }
            applyRuleIfMatchCondition();
        }

        // ---- 公开 API（外部调用） ----

        /** 任何规则应用路径（bindViewHolder / applyRuleBatch）调用后标记。 */
        void onRuleApplied() {
            mSelfTriggeredLayout = true;
        }

        /** 条件满足时执行全树扫描 + 批量规则应用。 */
        void applyRuleIfMatchCondition() {
            if (mApplying) return;
            mApplying = true;
            mSelfTriggeredLayout = true;
            try {
                Activity activity = Preconditions.checkNotNull(activityReference.get());
                List<RuleRecord> rules = mActRules.get(activity.getComponentName().getClassName());
                if (rules != null && !rules.isEmpty()) {
                    ViewController.getDefault().applyRuleBatch(activity, rules,
                            () -> mApplying = false);
                } else {
                    resetGuards();
                }
            } catch (Exception e) {
                Logger.w(TAG, "[Lifecycle] OnLayoutChange: applyRuleIfMatchCondition failed: "
                        + e.getMessage());
                resetGuards();
            }
        }

        // ---- 内部 ----

        private void resetGuards() {
            mApplying = false;
            mSelfTriggeredLayout = false;
        }
    }
}
