package com.kaisar.xposed.godmode.runtime;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.applier.RemoveApplier;
import com.kaisar.xposed.godmode.engine.applier.RuleApplier;
import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.Matcher;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.ActionSpec;
import com.kaisar.xposed.godmode.engine.rule.MatchFields;
import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将视图分发给 engine/applier 包中的规则执行器进行处理。
 * <p>
 * 根据 {@link RuleRecord#ruleTag} 决定执行策略：
 * <ul>
 *   <li>ruleTag 为 null 时，使用 {@link RemoveApplier} 执行移除操作</li>
 *   <li>ruleTag 非 null 时，使用 {@link ModifyApplier} 执行修改操作</li>
 * </ul>
 * <p>
 * 匹配使用 {@link CompositeMatcher}（{@link Matcher} 接口）。
 * <p>
 * <b>实例管理：</b>
 * <ul>
 *   <li>{@link #ViewController(String)} — Activity 级实例（推荐），Applier 缓存按 Activity 隔离</li>
 *   <li>{@link #getDefault()} — 进程级单例（向后兼容），缓存跨 Activity 共享</li>
 * </ul>
 */
public final class ViewController {

    private static final String TAG = "ViewController";

    /** 进程级单例，仅用于向后兼容 */
    private static volatile ViewController sInstance;

    private RuleApplier mModifyApplier;
    private RuleApplier mRemoveApplier;
    private Matcher mMatcher;

    /** Activity 类名，Activity 级实例时非 null */
    private final String mActivityClassName;

    // =========================================================================
    // 单例模式（向后兼容）
    // =========================================================================

    /**
     * 获取进程级单例实例（向后兼容过渡）。
     * <p>
     * 新代码应优先使用 {@link #ViewController(String)} 创建 Activity 级实例，
     * 以实现 Applier 缓存按 Activity 隔离。
     */
    @Deprecated
    public static ViewController getDefault() {
        if (sInstance == null) {
            synchronized (ViewController.class) {
                if (sInstance == null) {
                    sInstance = new ViewController((String) null);
                }
            }
        }
        return sInstance;
    }

    /** 进程级单例私有构造 */
    private ViewController(String activityClassName) {
        this.mActivityClassName = activityClassName;
    }

    /**
     * 创建 Activity 级 ViewController 实例。
     * <p>
     * Applier 缓存（RemoveApplier/ModifyApplier）将按此 Activity 隔离，
     * 不同 Activity 的缓存互不污染。Activity 销毁时调用 {@link #clearBlockedCache()}
     * 后进行 GC。
     *
     * @param activity Activity 实例（用于获取类名）
     */
    public ViewController(Activity activity) {
        this.mActivityClassName = activity != null
                ? activity.getComponentName().getClassName() : null;
    }

    /**
     * 获取当前绑定的 Activity 类名。
     */
    public String getActivityClassName() {
        return mActivityClassName;
    }

    // =========================================================================
    // Applier 懒加载
    // =========================================================================

    private synchronized RuleApplier getModifyApplier() {
        if (mModifyApplier == null) {
            mModifyApplier = new ModifyApplier(
                    path -> RuleServiceClient.getDefault().openImageFileDescriptor(path),
                    mActivityClassName);
        }
        return mModifyApplier;
    }

    private synchronized RuleApplier getRemoveApplier() {
        if (mRemoveApplier == null) {
            mRemoveApplier = mActivityClassName != null
                    ? new RemoveApplier(mActivityClassName)
                    : new RemoveApplier();
        }
        return mRemoveApplier;
    }

    public synchronized Matcher getMatcher() {
        if (mMatcher == null) {
            // CompositeMatcher 是无状态的策略容器，可安全复用
            mMatcher = new CompositeMatcher();
        }
        return mMatcher;
    }

    // =========================================================================
    // 公开 API
    // =========================================================================

    /** 清除已屏蔽控件的缓存 */
    public synchronized void clearBlockedCache() {
        if (mRemoveApplier != null) mRemoveApplier.clearCache();
        if (mModifyApplier != null) mModifyApplier.clearCache();
    }

    /** 将 app 模块的 RuleRecord 转换为 engine 模块的 RuleMatchSpec */
    private static RuleMatchSpec toEngineRule(RuleRecord appRule) {
        return RuleMapper.toEngine(appRule);
    }

    /** 批量应用规则（同步匹配 + 主线程应用）。内部调用 {@link #applyRuleBatch(Activity, List, Runnable)}。 */
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules) {
        applyRuleBatch(activity, rules, null);
    }

    /**
     * 批量应用规则（同步匹配 + 主线程应用）。
     * <p>
     * 匹配阶段（CompositeMatcher.matchView/matchAllViewsBatch）在当前线程同步执行，
     * 匹配完成后立即在同一线程应用规则。匹配操作为 O(depth) 的视图树遍历，耗时极短，
     * 无需异步到后台线程执行。
     * <p>
     * 同步执行确保规则在首帧渲染前生效，避免异步跳转（后台线程 + MAIN_HANDLER.post）
     * 引入的帧延迟导致被屏蔽元素短暂可见的闪烁问题。恢复为与 v5.3.0 一致的行为。
     * <p>
     * applyRule（修改 View 属性）必须在主线程执行，调用方需保证已处于主线程。
     *（当前所有调用路径：onRulesChanged、onGlobalLayout、decorView.post 均在主线程）
     *
     * @param activity   目标 Activity
     * @param rules      待应用的规则列表
     * @param onComplete 全部匹配+应用完成后在主线程回调（用于 mApplying 重置等场景），可为 null
     */
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules, Runnable onComplete) {
        ViewGroup decorView = activity != null && activity.getWindow() != null
                ? (ViewGroup) activity.getWindow().getDecorView() : null;
        if (decorView == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // ── 同步匹配 ──
        List<MatchTask> pending = new ArrayList<>();

        // 收集所有 repeatable 规则，使用批次匹配（单次视图树遍历）
        List<RuleRecord> batchRules = new ArrayList<>();
        List<MatchFields> batchSpecs = new ArrayList<>();
        for (RuleRecord rule : rules) {
            if (rule.isRepeatable()) {
                batchRules.add(rule);
                batchSpecs.add(toEngineRule(rule).getMatchSpec());
            }
        }
        if (!batchSpecs.isEmpty()) {
            try {
                Map<Integer, List<View>> batchResults =
                        getMatcher().matchAllViewsBatch(decorView, batchSpecs);
                for (Map.Entry<Integer, List<View>> entry : batchResults.entrySet()) {
                    RuleRecord rule = batchRules.get(entry.getKey());
                    for (View v : entry.getValue()) {
                        if (v != null) pending.add(new MatchTask(v, rule));
                    }
                }
            } catch (Exception e) {
                Logger.w(TAG, "[ViewController] batch match failed", e);
            }
        }

        // 非 repeatable 规则：逐条 matchView（每次 O(depth) 不影响）
        for (RuleRecord rule : rules) {
            if (rule.isRepeatable()) continue;
            try {
                View view = getMatcher().matchView(decorView,
                        toEngineRule(rule).getMatchSpec());
                if (view != null) {
                    pending.add(new MatchTask(view, rule));
                }
            } catch (Exception e) {
                Logger.w(TAG, "[ViewController] match failed", e);
            }
        }

        // ── 同步应用 ──
        int applied = 0;
        for (MatchTask task : pending) {
            try {
                if (applyRule(task.view, task.rule)) applied++;
            } catch (Exception e) {
                Logger.w(TAG, "[ViewController] apply rule failed", e);
            }
        }
        if (onComplete != null) onComplete.run();
    }

    /**
     * CARD 模式目标解析：若规则为 CARD 模式且当前视图为卡片根，则通过 itemPath
     * 导航到内部目标元素；若当前视图已是目标元素（类名匹配），则直接返回避免重复导航。
     *
     * @param v    当前视图（可能是卡片根 or 已解析的内部元素）
     * @param rule 目标规则
     * @return 解析后的目标视图
     */
    private static View resolveCardTarget(View v, RuleRecord rule) {
        TargetLevel targetLevel = rule.getTargetLevel();
        if (targetLevel != TargetLevel.CARD) return v;
        if (rule.getItemPath() == null || rule.getItemPath().length == 0) return v;

        // 智能跳过：若 v 已是目标元素（来自 matchAllViewsBatch 的预解析结果），
        // 则无需再次导航。只对 CARD 模式生效——旧规则 targetLevel=null，此处早已返回。
        String viewClass = rule.getViewClass();
        if (viewClass != null && v.getClass().getName().equals(viewClass)) {
            return v;
        }

        // v 是卡片根，需要导航到内部目标元素
        View target = ViewTraversal.findViewByItemPath(v, rule.getItemPath(), 0);
        return target != null ? target : v;
    }

    /** 应用单条规则 */
    public boolean applyRule(View v, RuleRecord viewRule) {
        if (v == null || viewRule == null) return false;
        RuleMatchSpec engineRule = toEngineRule(viewRule);
        ActionSpec spec = engineRule.getActionSpec();

        v = resolveCardTarget(v, viewRule);

        if (viewRule.isModifyRule()) {
            return getModifyApplier().apply(v, spec);
        } else {
            return getRemoveApplier().apply(v, spec);
        }
    }

    /** 批量撤销规则 */
    public void revokeRuleBatch(Activity activity, List<RuleRecord> rules) {
        ViewGroup decorView = activity != null && activity.getWindow() != null
                ? (ViewGroup) activity.getWindow().getDecorView() : null;
        if (decorView == null) return;
        for (RuleRecord rule : rules) {
            try {
                RuleMatchSpec engineRule = toEngineRule(rule);
                if (rule.isRepeatable()) {
                    List<View> views = getMatcher().matchAllViews(decorView, engineRule.getMatchSpec());
                    if (views != null) {
                        for (View v : views) {
                            if (v != null) revokeRule(v, rule);
                        }
                    }
                    continue;
                }
                View view = getMatcher().matchView(decorView, engineRule.getMatchSpec());
                if (view == null) {
                    Logger.w(TAG, "[ViewController] revoke rule fail (act=" + activity
                            + "): not match any view");
                    continue;
                }
                revokeRule(view, rule);
            } catch (Exception e) {
                Logger.w(TAG, "[ViewController] revoke rule failed", e);
            }
        }
    }

    /** 撤销单条规则 */
    public void revokeRule(View v, RuleRecord viewRule) {
        if (v == null || viewRule == null) return;
        RuleMatchSpec engineRule = toEngineRule(viewRule);
        ActionSpec spec = engineRule.getActionSpec();

        v = resolveCardTarget(v, viewRule);

        if (viewRule.isModifyRule()) {
            getModifyApplier().revoke(v, spec);
        } else {
            if (!getRemoveApplier().revoke(v, spec)) {
                Logger.w(TAG, "[ViewController] revokeRule: RemoveApplier.revoke() returned false"
                        + " for view=" + v + " rule=" + viewRule);
            }
        }
    }

    /**
     * 递归撤销 root 子树中所有被应用过规则的 View（不依赖规则集，纯缓存操作）。
     * <p>
     * 用于 onViewRecycled 回调——当 RecyclerView 回收 itemView 时，
     * 递归遍历其子树，撤销所有被 RemoveApplier/ModifyApplier 应用过的子 View。
     * 未被应用过的 View 则 revokeForView 返回 false（无副作用）。
     * <p>
     * CARD 模式自动兼容：卡片根 apply 时 itemPath 导航到内部子 View，
     * 子 View 进入缓存 → onViewRecycled 递归遍历卡片根的子树 → 子 View 被命中。
     *
     * @param root 被回收的 itemView 根
     */
    public void revokeAllRules(View root) {
        if (root == null) return;
        // 先撤销 root 本身
        revokeForView(root);
        // 递归子树
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                revokeAllRules(vg.getChildAt(i));
            }
        }
    }

    /** 对单个 View 撤销（RemoveApplier + ModifyApplier），不依赖规则集 */
    private void revokeForView(View view) {
        if (mRemoveApplier != null && mRemoveApplier instanceof RemoveApplier) {
            ((RemoveApplier) mRemoveApplier).revokeForView(view);
        }
        if (mModifyApplier != null && mModifyApplier instanceof ModifyApplier) {
            ((ModifyApplier) mModifyApplier).revokeForView(view);
        }
    }

    // ── 异步匹配辅助 ──

    /** 异步批量匹配过程中暂存 (View, RuleRecord) 对，等待主线程 apply。 */
    private static final class MatchTask {
        final View view;
        final RuleRecord rule;

        MatchTask(View view, RuleRecord rule) {
            this.view = view;
            this.rule = rule;
        }
    }
}
