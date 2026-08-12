package com.kaisar.xposed.godmode.orchestrator;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.kaisar.xposed.godmode.engine.event.ActivityLifecycleEvent;
import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.event.Subscribe;
import com.kaisar.xposed.godmode.engine.rule.RuleDiff;
import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.Matcher;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.orchestrator.ViewController;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 规则生命周期管理器 — 接收 EventBus 事件，协调 Activity 生命周期与规则应用。
 * <p>
 * 接管运行时三项核心职责：
 * <ol>
 *   <li>订阅 {@link ActivityLifecycleEvent} — 在 Activity RESUME 时注册布局监听并应用规则，
 *       DESTROY 时清理资源</li>
 *   <li>订阅 {@link RulesChangedEvent} — 计算规则差集，撤销旧规则、应用新规则</li>
 *   <li>实现 {@link RecyclerAdapterHook.Delegate} — 为 RecyclerView 钩子提供缓存清理和重应用调度</li>
 * </ol>
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
 * <p>
 * 使用 {@link #getInstance()} 获取单例，由注入层注册到 EventBus。
 */
public final class RuleLifecycleManager implements RecyclerAdapterHook.Delegate {

    private static final String TAG = "RuleLifecycleManager";

    /** 防抖延迟：确保 RecyclerView 在 notifyDataSetChanged 后完成布局 */
    private static final long REAPPLY_DEBOUNCE_MS = 50L;

    private static volatile RuleLifecycleManager sInstance;

    // ===== 实例状态 =====

    /** 已注册 OnGlobalLayoutListener 的 Activity */
    private final WeakHashMap<Activity, OnLayoutChangeListener> mActivities = new WeakHashMap<>();

    /** 按 Activity 隔离的 ViewController（Applier 缓存隔离） */
    private final WeakHashMap<Activity, ViewController> mViewControllers = new WeakHashMap<>();

    /** 防抖重应用待办 */
    private final Map<Activity, Runnable> mPendingReapply = new WeakHashMap<>();

    /** 防抖 Handler（主线程） */
    private final Handler mDebounceHandler = new Handler(Looper.getMainLooper());

    // ===== 单例 =====

    private RuleLifecycleManager() {}

    /**
     * 获取 RuleLifecycleManager 单例。
     */
    public static RuleLifecycleManager getInstance() {
        RuleLifecycleManager result = sInstance;
        if (result == null) {
            synchronized (RuleLifecycleManager.class) {
                result = sInstance;
                if (result == null) {
                    result = new RuleLifecycleManager();
                    sInstance = result;
                }
            }
        }
        return result;
    }

    // ===================================================================
    // EventBus @Subscribe — Activity 生命周期事件
    // ===================================================================

    /**
     * 处理 Activity 生命周期事件。
     * <p>
     * 由 LifecycleHooks 通过 EventBus 发布。
     */
    @Subscribe
    public void onActivityLifecycle(ActivityLifecycleEvent event) {
        if (event == null) return;
        switch (event.getType()) {
            case RESUME:
                onActivityResume(event.getActivity());
                break;
            case DESTROY:
                onActivityDestroy(event.getActivity());
                break;
        }
    }

    private void onActivityResume(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        ViewGroup decorView = getDecorView(activity);
        if (decorView == null) return;

        // 首次见到此 Activity — 注册 OnGlobalLayoutListener
        if (!mActivities.containsKey(activity)) {
            OnLayoutChangeListener listener = new OnLayoutChangeListener(activity);
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            mActivities.put(activity, listener);

            // 布局就绪后立即执行第一次全树规则匹配
            decorView.post(listener::applyRuleIfMatchCondition);
            scheduleRuleReapplication(activity);
        }

        // 创建 Activity 级 ViewController（缓存隔离）
        if (!mViewControllers.containsKey(activity)) {
            mViewControllers.put(activity, new ViewController(activity));
        }

        // 安装 RecyclerView 钩子（幂等：仅首次生效）
        RecyclerAdapterHook.install(activity, this);
    }

    private void onActivityDestroy(Activity activity) {
        if (activity == null) return;

        // 移除 OnGlobalLayoutListener
        OnLayoutChangeListener listener = mActivities.remove(activity);
        removeLayoutListener(activity, listener);
        if (listener != null) {
            listener.dispose();
        }

        // 清理 Activity 级 ViewController 及其 Applier 缓存
        ViewController vc = mViewControllers.remove(activity);
        if (vc != null) {
            vc.clearBlockedCache();
            // 清除 RecyclerView 收集缓存，释放对已销毁 DecorView 的引用
            Matcher matcher = vc.getMatcher();
            if (matcher instanceof CompositeMatcher) {
                ((CompositeMatcher) matcher).invalidateRecyclerCache();
            }
        }

        // 取消防抖重应用
        synchronized (mPendingReapply) {
            Runnable r = mPendingReapply.remove(activity);
            if (r != null) {
                mDebounceHandler.removeCallbacks(r);
            }
        }
    }

    // ===================================================================
    // EventBus @Subscribe — 规则变更事件
    // ===================================================================

    /**
     * 处理规则变更事件。
     * <p>
     * 四步流程：
     * <ol>
     *   <li>计算差集 — 旧规则中不在新规则内的部分需要撤销</li>
     *   <li>撤销旧规则 — 必须在失效匹配缓存之前，revoke 依赖 applier baseline</li>
     *   <li>替换 + 应用新规则</li>
     *   <li>防抖重应用 — 50ms 延迟确保布局稳定</li>
     * </ol>
     */
    @Subscribe
    public void onRulesChanged(RulesChangedEvent event) {
        if (event == null) return;
        ActRules newRules = toActRules(event.rules);
        if (newRules == null) {
            Logger.w(TAG, "onRulesChanged received null rules");
            return;
        }

        if (!RuleManager.isInitialized()) {
            Logger.w(TAG, "onRulesChanged skipped — RuleManager not initialized");
            return;
        }

        ActRules currentRules = RuleManager.get().getRules();
        RuleDiff diff = computeRuntimeDiff(currentRules, newRules);
        if (diff.isEmpty()) {
            // 展示元数据可能变化；更新快照，但不重建运行时效果。
            RuleManager.get().replaceRules(newRules);
            return;
        }

        // Step 1: 撤销被删除/修改的旧规则，撤销依赖 applier baseline。
        if (!diff.toRevoke.isEmpty()) {
            revokeRulesForActivities(mapToActRules(diff.toRevoke));
        }

        // Step 2: 仅失效匹配位置缓存，不能清除撤销所需的 baseline。
        invalidateMatcherCaches();

        // Step 3: 替换规则集并应用新规则
        RuleManager.get().replaceRules(newRules);
        if (!diff.toApply.isEmpty()) {
            applyRulesForActivities(mapToActRules(diff.toApply));
        }

        // Step 4: 防抖重应用（仅对受影响的 Activity）
        scheduleReapplyForActivities(newRules);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static RuleDiff computeRuntimeDiff(ActRules currentRules, ActRules newRules) {
        return RuleDiff.compute(
                (Map) currentRules,
                (Map) newRules,
                (a, b) -> ((RuleRecord) a).equals(b),
                (a, b) -> RuleManager.runtimeContentEquals(
                        (RuleRecord) a, (RuleRecord) b));
    }

    // ===================================================================
    // RecyclerAdapterHook.Delegate 实现
    // ===================================================================

    @Override
    public void invalidateMatcherCaches() {
        for (ViewController vc : mViewControllers.values()) {
            if (vc != null) {
                vc.invalidateMatcherCache();
            }
        }
        ViewController.getDefault().invalidateMatcherCache();
    }

    @Override
    public void scheduleReapplyForActivities() {
        if (RuleManager.isInitialized()) {
            scheduleReapplyForActivities(RuleManager.get().getRules());
        }
    }

    // ===================================================================
    // ViewController 辅助
    // ===================================================================

    /**
     * 获取指定 Activity 的 ViewController 实例。
     * <p>
     * 优先返回 Activity 级实例（缓存隔离），不存在时回退到进程级单例（向后兼容）。
     */
    @Override
    public ViewController getViewController(Activity activity) {
        ViewController vc = mViewControllers.get(activity);
        return vc != null ? vc : ViewController.getDefault();
    }

    // ===================================================================
    // 规则撤销/应用调度
    // ===================================================================

    private void revokeRulesForActivities(ActRules toRevoke) {
        forEachMatchingActivity(toRevoke, (activity, rules) -> {
            List<RuleRecord> revRemove = new ArrayList<>();
            List<RuleRecord> revModify = new ArrayList<>();
            for (RuleRecord r : rules) {
                if (r.isModifyRule()) {
                    revModify.add(r);
                } else {
                    revRemove.add(r);
                }
            }
            if (!revRemove.isEmpty()) {
                getViewController(activity).revokeRuleBatch(activity, revRemove);
            }
            if (!revModify.isEmpty()) {
                getViewController(activity).revokeRuleBatch(activity, revModify);
            }
        });
    }

    private void applyRulesForActivities(ActRules rules) {
        forEachMatchingActivity(rules, (activity, ruleList) -> {
            if (!ruleList.isEmpty()) {
                getViewController(activity).applyRuleBatch(activity, ruleList);
            }
        });
    }

    private void scheduleReapplyForActivities(ActRules rules) {
        forEachMatchingActivity(rules, (activity, ruleList) ->
                scheduleRuleReapplication(activity));
    }

    // ===================================================================
    // Activity 安全迭代（WeakHashMap GC 防护）
    // ===================================================================

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
    private void forEachMatchingActivity(ActRules rules,
                                          BiConsumer<Activity, List<RuleRecord>> action) {
        for (Map.Entry<String, List<RuleRecord>> entry : rules.entrySet()) {
            String targetClass = entry.getKey();
            List<RuleRecord> ruleList = entry.getValue();
            forEachLiveActivity(activity -> {
                if (TextUtils.equals(
                        activity.getComponentName().getClassName(), targetClass)) {
                    action.accept(activity, ruleList);
                }
            });
        }
    }

    // ===================================================================
    // 防抖重应用调度
    // ===================================================================

    private void scheduleRuleReapplication(final Activity activity) {
        synchronized (mPendingReapply) {
            Runnable existing = mPendingReapply.get(activity);
            if (existing != null) {
                mDebounceHandler.removeCallbacks(existing);
            }
            Runnable r = () -> {
                synchronized (mPendingReapply) {
                    mPendingReapply.remove(activity);
                }
                OnLayoutChangeListener listener = mActivities.get(activity);
                if (listener != null && !listener.isDisposed()) {
                    listener.applyRuleIfMatchCondition();
                }
            };
            mPendingReapply.put(activity, r);
            mDebounceHandler.postDelayed(r, REAPPLY_DEBOUNCE_MS);
        }
    }

    // ===================================================================
    // 工具方法
    // ===================================================================

    private static ViewGroup getDecorView(Activity activity) {
        if (activity == null || activity.getWindow() == null) return null;
        try {
            return (ViewGroup) activity.getWindow().getDecorView();
        } catch (Exception e) {
            Logger.w(TAG, "getDecorView failed: " + e.getMessage());
            return null;
        }
    }

    private static void removeLayoutListener(Activity activity,
                                              OnLayoutChangeListener listener) {
        if (listener == null) return;
        ViewGroup decorView = getDecorView(activity);
        if (decorView == null) return;
        ViewTreeObserver observer = decorView.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnGlobalLayoutListener(listener);
        }
    }

    /**
     * 将 engine 层 {@code Map<String, ?>} 安全转型为 app 层 {@link ActRules}。
     * <p>
     * 架构限制：RulesChangedEvent 位于 engine 层，无法声明 {@code Map<String, List<RuleRecord>>} 泛型。
     */
    @SuppressWarnings("unchecked")
    private static ActRules toActRules(Map<String, ?> rules) {
        return (rules instanceof ActRules) ? (ActRules) rules : new ActRules();
    }

    /**
     * 将 {@link RuleDiff} 的泛型差集结果安全转换为 {@link ActRules}。
     * <p>
     * {@code RuleDiff.toRevoke} 和 {@code RuleDiff.toApply} 的类型为
     * {@code Map<String, List<?>>}，因 engine 层无法引用 app 层的 RuleRecord 类型。
     * 此方法执行安全的向下转型。
     *
     * @param map RuleDiff 的差集 map（toRevoke 或 toApply）
     * @return 转型后的 ActRules
     */
    @SuppressWarnings("unchecked")
    private static ActRules mapToActRules(Map<String, List<?>> map) {
        ActRules result = new ActRules();
        for (Map.Entry<String, List<?>> entry : map.entrySet()) {
            List<RuleRecord> rules = new ArrayList<>();
            for (Object item : entry.getValue()) {
                rules.add((RuleRecord) item);
            }
            result.put(entry.getKey(), rules);
        }
        return result;
    }

    // ===================================================================
    // OnLayoutChangeListener — 全局布局监听 + 自身触发跳过守卫
    // ===================================================================

    /**
     * {@link ViewTreeObserver.OnGlobalLayoutListener} 实现。
     * <p>
     * 在每次全局布局完成后执行全树规则匹配扫描。
     * 包含同步守卫（防止重入）和异步守卫（防止规则应用触发布局反馈循环）。
     */
    final class OnLayoutChangeListener implements ViewTreeObserver.OnGlobalLayoutListener {

        final WeakReference<Activity> activityReference;

        /** 同步守卫：同一调用链内防止重入 */
        private volatile boolean mApplying;
        private volatile boolean mDisposed;

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

        @Override
        public void onGlobalLayout() {
            if (mDisposed) return;
            if (mApplying) return;
            if (mSelfTriggeredLayout) {
                mSelfTriggeredLayout = false;
                return;
            }
            applyRuleIfMatchCondition();
        }

        /** 任何规则应用路径（bindViewHolder / applyRuleBatch）调用后标记。 */
        void onRuleApplied() {
            if (mDisposed) return;
            mSelfTriggeredLayout = true;
        }

        /** 条件满足时执行全树扫描 + 批量规则应用。 */
        void applyRuleIfMatchCondition() {
            if (mDisposed || mApplying) return;
            mApplying = true;
            mSelfTriggeredLayout = true;
            try {
                Activity activity = Preconditions.checkNotNull(activityReference.get());
                if (mDisposed || activity.isFinishing()
                        || mActivities.get(activity) != this) {
                    resetGuards();
                    return;
                }
                // 每次规则匹配周期前清空 RecyclerView 收集缓存，
                // 防止 Fragment 切换后新增的 RecyclerView 被过时缓存遗漏
                ViewController vc = getViewController(activity);
                if (vc != null) {
                    Matcher m = vc.getMatcher();
                    if (m instanceof CompositeMatcher) {
                        ((CompositeMatcher) m).invalidateRecyclerCache();
                    }
                }
                List<RuleRecord> rules = RuleManager.isInitialized()
                        ? RuleManager.get().getRules().get(
                                activity.getComponentName().getClassName())
                        : null;
                if (rules != null && !rules.isEmpty()) {
                    getViewController(activity).applyRuleBatch(activity, rules,
                            () -> mApplying = false);
                } else {
                    resetGuards();
                }
            } catch (Exception e) {
                Logger.w(TAG, "applyRuleIfMatchCondition failed: " + e.getMessage());
                resetGuards();
            }
        }

        private void resetGuards() {
            mApplying = false;
            mSelfTriggeredLayout = false;
        }

        boolean isDisposed() {
            return mDisposed;
        }

        void dispose() {
            mDisposed = true;
            activityReference.clear();
            resetGuards();
        }
    }
}
