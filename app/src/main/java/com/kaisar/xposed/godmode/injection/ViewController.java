package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.applier.RemoveApplier;
import com.kaisar.xposed.godmode.engine.applier.RuleApplier;
import com.kaisar.xposed.godmode.engine.matcher.ViewFinder;
import com.kaisar.xposed.godmode.engine.util.FieldMapper;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.engine.util.Preconditions;

import java.util.List;

/**
 * 视图控制器 — 使用 engine/applier 体系应用/撤销规则。
 * <p>
 * 根据 {@link ViewRule#ruleTag} 自动路由：
 * <ul>
 *   <li>ruleTag 为 null 或空 → 移除规则，委托 {@link RemoveApplier}</li>
 *   <li>ruleTag 非空 → 修改规则，委托 {@link ModifyApplier}</li>
 * </ul>
 * <p>
 * 通过 {@link #getDefault()} 获取共享实例。
 */
public final class ViewController {

    private static volatile ViewController sInstance;

    private RuleApplier mModifyApplier;
    private RuleApplier mRemoveApplier;

    // =========================================================================
    // 单例访问
    // =========================================================================

    /** 获取共享实例（延迟初始化，线程安全）。 */
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
                    path -> GodModeManager.getDefault().openImageFileDescriptor(path));
        }
        return mModifyApplier;
    }

    private RuleApplier getRemoveApplier() {
        if (mRemoveApplier == null) {
            mRemoveApplier = new RemoveApplier();
        }
        return mRemoveApplier;
    }

    // =========================================================================
    // 公开 API
    // =========================================================================

    /** 清空已屏蔽视图的缓存。 */
    public void clearBlockedCache() {
        if (mRemoveApplier != null) mRemoveApplier.clearCache();
        if (mModifyApplier != null) mModifyApplier.clearCache();
    }

    /** 将 app 模块的 ViewRule 转换为 engine 的 ViewRule。 */
    private static com.kaisar.xposed.godmode.engine.rule.ViewRule toEngineRule(ViewRule appRule) {
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule =
                new com.kaisar.xposed.godmode.engine.rule.ViewRule();
        FieldMapper.copyFields(appRule, engineRule);
        return engineRule;
    }

    /** 批量应用规则。 */
    public void applyRuleBatch(Activity activity, List<ViewRule> rules) {
        int appliedCount = 0;
        ViewGroup decorView = activity != null && activity.getWindow() != null
                ? (ViewGroup) activity.getWindow().getDecorView() : null;
        if (decorView == null) return;
        String packageName = activity.getPackageName();
        for (ViewRule rule : rules) {
            try {
                com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule = toEngineRule(rule);
                if (rule.isRepeatable()) {
                    List<View> views = ViewFinder.findAllViewsBestMatch(decorView, engineRule,
                            activity.getPackageManager(), packageName);
                    if (views != null) {
                        for (View v : views) {
                            if (v != null && applyRule(v, rule)) appliedCount++;
                        }
                    }
                    continue;
                }
                View view = ViewFinder.findViewBestMatch(decorView, engineRule,
                        activity.getPackageManager(), packageName);
                Preconditions.checkNotNull(view, "apply rule fail not match any view");
                if (applyRule(view, rule)) appliedCount++;
            } catch (NullPointerException e) {
                Logger.w(TAG, "[ViewController] Failed: " + activity + "#" + rule.viewClass
                        + " block failed: " + e.getMessage());
            }
        }
        if (appliedCount > 0) {
            Logger.d(TAG, "[ViewController] applied " + appliedCount + " rules for " + activity);
        }
    }

    /** 应用单条规则。 */
    public boolean applyRule(View v, ViewRule viewRule) {
        if (v == null || viewRule == null) return false;
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule = toEngineRule(viewRule);
        if (viewRule.isModifyRule()) {
            return getModifyApplier().apply(v, engineRule);
        } else {
            return getRemoveApplier().apply(v, engineRule);
        }
    }

    /** 批量撤销规则。 */
    public void revokeRuleBatch(Activity activity, List<ViewRule> rules) {
        ViewGroup decorView = activity != null && activity.getWindow() != null
                ? (ViewGroup) activity.getWindow().getDecorView() : null;
        if (decorView == null) return;
        String packageName = activity.getPackageName();
        for (ViewRule rule : rules) {
            try {
                com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule = toEngineRule(rule);
                if (rule.isRepeatable()) {
                    List<View> views = ViewFinder.findAllViewsBestMatch(decorView, engineRule,
                            activity.getPackageManager(), packageName);
                    if (views != null) {
                        for (View v : views) {
                            if (v != null) revokeRule(v, rule);
                        }
                    }
                    continue;
                }
                View view = ViewFinder.findViewBestMatch(decorView, engineRule,
                        activity.getPackageManager(), packageName);
                Preconditions.checkNotNull(view, "revoke rule fail can't found block view");
                revokeRule(view, rule);
            } catch (NullPointerException e) {
                Logger.w(TAG, "[ViewController] revoke rule fail (act=" + activity + "): "
                        + e.getMessage());
            }
        }
    }

    /** 撤销单条规则。 */
    public void revokeRule(View v, ViewRule viewRule) {
        if (v == null || viewRule == null) return;
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule = toEngineRule(viewRule);
        if (viewRule.isModifyRule()) {
            getModifyApplier().revoke(v, engineRule);
        } else {
            getRemoveApplier().revoke(v, engineRule);
        }
    }
}
