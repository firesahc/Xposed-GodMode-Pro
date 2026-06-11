package com.kaisar.xposed.godmode.injection;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.applier.RemoveApplier;
import com.kaisar.xposed.godmode.engine.applier.RuleApplier;
import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.IMatcher;
import com.kaisar.xposed.godmode.engine.matcher.TargetLevel;
import com.kaisar.xposed.godmode.engine.matcher.ViewTraversal;
import com.kaisar.xposed.godmode.engine.rule.ActionSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.ThreadPools;
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
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
 * 匹配使用 {@link CompositeMatcher}（{@link IMatcher} 接口）。
 * 通过 {@link #getDefault()} 获取单例实例。
 */
public final class ViewController {

    private static final String TAG = "ViewController";

    /** 主线程 Handler，用于异步匹配完成后切回主线程应用规则 */
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile ViewController sInstance;

    private RuleApplier mModifyApplier;
    private RuleApplier mRemoveApplier;
    private IMatcher mMatcher;

    // =========================================================================
    // 单例模式
    // =========================================================================

    /** 获取单例实例，使用双重检查锁定（DCL）保证线程安全。*/
    public static ViewController getDefault() {
        if (sInstance == null) {
            synchronized (ViewController.class) {
                if (sInstance == null) {
                    sInstance = new ViewController();
                }
            }
        }
        return sInstance;
    }

    private ViewController() {}

    // =========================================================================
    // Applier 懒加载
    // =========================================================================

    private RuleApplier getModifyApplier() {
        if (mModifyApplier == null) {
            mModifyApplier = new ModifyApplier(
                    path -> RuleServiceClient.getDefault().openImageFileDescriptor(path));
        }
        return mModifyApplier;
    }

    private RuleApplier getRemoveApplier() {
        if (mRemoveApplier == null) {
            mRemoveApplier = new RemoveApplier();
        }
        return mRemoveApplier;
    }

    private IMatcher getMatcher() {
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
    public void clearBlockedCache() {
        if (mRemoveApplier != null) mRemoveApplier.clearCache();
        if (mModifyApplier != null) mModifyApplier.clearCache();
    }

    /** 将 app 模块的 RuleRecord 转换为 engine 模块的 RuleMatchSpec */
    private static RuleMatchSpec toEngineRule(RuleRecord appRule) {
        return RuleMapper.toEngine(appRule);
    }

    /** 批量应用规则（异步匹配 + 主线程应用）。内部调用 {@link #applyRuleBatch(Activity, List, Runnable)}。 */
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules) {
        applyRuleBatch(activity, rules, null);
    }

    /**
     * 批量应用规则（异步匹配 + 主线程应用）。
     * <p>
     * 匹配阶段（CompositeMatcher.matchView/matchAllViews）在 {@link ThreadPools#GENERAL} 上执行，
     * 避免阻塞主线程导致 UI 卡顿。匹配完成后切回主线程执行 applyRule/revokeRule。
     * <p>
     * applyRule（修改 View 属性）必须在主线程执行，因此分为两阶段：
     * <ol>
     *   <li>ThreadPools.GENERAL 上执行匹配，收集匹配结果 (View, RuleRecord) 列表</li>
     *   <li>MAIN_HANDLER.post() 回到主线程，批量应用匹配结果</li>
     * </ol>
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

        // 引用锚定（DecorView 在 async 执行期间可能失效，但匹配失败会被 catch 静默处理）
        final ViewGroup decorViewRef = decorView;
        ThreadPools.GENERAL.execute(() -> {
            // ── 阶段 1：异步匹配（GENERAL 线程池） ──
            List<MatchTask> pending = new ArrayList<>();

            // 收集所有 repeatable 规则，使用批次匹配（单次视图树遍历）
            List<RuleRecord> batchRules = new ArrayList<>();
            List<com.kaisar.xposed.godmode.engine.rule.MatchSpec> batchSpecs = new ArrayList<>();
            for (RuleRecord rule : rules) {
                if (rule.isRepeatable()) {
                    batchRules.add(rule);
                    batchSpecs.add(toEngineRule(rule).getMatchSpec());
                }
            }
            if (!batchSpecs.isEmpty()) {
                try {
                    Map<Integer, List<View>> batchResults =
                            getMatcher().matchAllViewsBatch(decorViewRef, batchSpecs);
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
                    View view = getMatcher().matchView(decorViewRef,
                            toEngineRule(rule).getMatchSpec());
                    if (view != null) {
                        pending.add(new MatchTask(view, rule));
                    }
                } catch (Exception e) {
                    Logger.w(TAG, "[ViewController] async match failed", e);
                }
            }

            if (pending.isEmpty()) {
                if (onComplete != null) MAIN_HANDLER.post(onComplete);
                return;
            }

            // ── 阶段 2：主线程应用 ──
            MAIN_HANDLER.post(() -> {
                int applied = 0;
                for (MatchTask task : pending) {
                    try {
                        if (applyRule(task.view, task.rule)) applied++;
                    } catch (Exception e) {
                        Logger.w(TAG, "[ViewController] apply rule failed", e);
                    }
                }
                if (applied > 0) {
                    Logger.d(TAG, "[ViewController] async applied " + applied + " rules for " + activity);
                }
                if (onComplete != null) onComplete.run();
            });
        });
    }

    /** 应用单条规则 */
    public boolean applyRule(View v, RuleRecord viewRule) {
        if (v == null || viewRule == null) return false;
        RuleMatchSpec engineRule = toEngineRule(viewRule);
        ActionSpec spec = engineRule.getActionSpec();

        // CARD 模式：从卡片根导航到内部目标元素再执行操作（修改或移除）。
        // 匹配阶段（matchAllViewsBatch）对 CARD 模式返回的是卡片根 View，
        // 但用户实际选中的是卡片内部的元素，因此需要通过 itemPath 导航到具体元素。
        // 若导航失败（v 可能已是内部元素，编辑器首次应用场景），降级到直接操作 v。
        TargetLevel targetLevel = viewRule.getTargetLevel();
        if (targetLevel == TargetLevel.CARD
                && viewRule.getItemPath() != null && viewRule.getItemPath().length > 0) {
            View target = ViewTraversal.findViewByItemPath(v, viewRule.getItemPath(), 0);
            if (target != null) {
                v = target;
            }
            // fall through: v 可能是内部元素，直接操作
        }

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

        // CARD 模式：从卡片根导航到内部目标元素再撤销操作（修改或移除）。
        // 与 applyRule() 对称，确保 CARD 模式撤销时也定位到用户实际选中的内部元素。
        // 若导航失败（v 可能已是内部元素），降级到直接撤销 v。
        TargetLevel targetLevel = viewRule.getTargetLevel();
        if (targetLevel == TargetLevel.CARD
                && viewRule.getItemPath() != null && viewRule.getItemPath().length > 0) {
            View target = ViewTraversal.findViewByItemPath(v, viewRule.getItemPath(), 0);
            if (target != null) {
                v = target;
            }
            // fall through: v 可能是内部元素，直接撤销
        }

        if (viewRule.isModifyRule()) {
            getModifyApplier().revoke(v, spec);
        } else {
            getRemoveApplier().revoke(v, spec);
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
