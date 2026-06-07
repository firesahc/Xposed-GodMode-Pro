package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.applier.RemoveApplier;
import com.kaisar.xposed.godmode.engine.applier.RuleApplier;
import com.kaisar.xposed.godmode.engine.util.FieldMapper;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.util.List;

/**
 * 视图控制器 — 使用 engine/applier 体系应用/撤销规则。
 * <p>
 * 根据 {@link ViewRule#ruleTag} 自动路由：
 * <ul>
 *   <li>ruleTag 为 null 或空 → 移除规则，委托 {@link RemoveApplier}</li>
 *   <li>ruleTag 非空 → 修改规则，委托 {@link ModifyApplier}</li>
 * </ul>
 */
public final class ViewController {

    private static RuleApplier sModifyApplier;
    private static RuleApplier sRemoveApplier;

    private static RuleApplier getModifyApplier() {
        if (sModifyApplier == null) {
            sModifyApplier = new ModifyApplier(
                    path -> GodModeManager.getDefault().openImageFileDescriptor(path));
        }
        return sModifyApplier;
    }

    private static RuleApplier getRemoveApplier() {
        if (sRemoveApplier == null) {
            sRemoveApplier = new RemoveApplier();
        }
        return sRemoveApplier;
    }

    public static void clearBlockedCache() {
        if (sRemoveApplier != null) sRemoveApplier.clearCache();
        if (sModifyApplier != null) sModifyApplier.clearCache();
    }

    /** 将 app 模块的 ViewRule（Transport 版）转换为 engine 的 ViewRule（Computation 版）。 */
    private static com.kaisar.xposed.godmode.engine.rule.ViewRule toEngineRule(ViewRule appRule) {
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule =
                new com.kaisar.xposed.godmode.engine.rule.ViewRule();
        FieldMapper.copyFields(appRule, engineRule);
        return engineRule;
    }

    public static void applyRuleBatch(Activity activity, List<ViewRule> rules) {
        int appliedCount = 0;
        for (ViewRule rule : rules) {
            try {
                if (rule.isRepeatable()) {
                    List<View> views = ViewHelper.findAllViewsBestMatch(activity, rule);
                    if (views != null) {
                        for (View v : views) {
                            if (v != null && applyRule(v, rule)) appliedCount++;
                        }
                    }
                    continue;
                }
                View view = ViewHelper.findViewBestMatch(activity, rule);
                Preconditions.checkNotNull(view, "apply rule fail not match any view");
                if (applyRule(view, rule)) appliedCount++;
            } catch (NullPointerException e) {
                Logger.w(TAG, "[ViewController] Failed: " + activity + "#" + rule.viewClass + " block failed: " + e.getMessage());
            }
        }
        if (appliedCount > 0) {
            Logger.d(TAG, "[ViewController] applied " + appliedCount + " rules for " + activity);
        }
    }

    public static boolean applyRule(View v, ViewRule viewRule) {
        if (v == null || viewRule == null) return false;
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule = toEngineRule(viewRule);
        if (viewRule.isModifyRule()) {
            return getModifyApplier().apply(v, engineRule);
        } else {
            return getRemoveApplier().apply(v, engineRule);
        }
    }

    public static void revokeRuleBatch(Activity activity, List<ViewRule> rules) {
        for (ViewRule rule : rules) {
            try {
                if (rule.isRepeatable()) {
                    List<View> views = ViewHelper.findAllViewsBestMatch(activity, rule);
                    if (views != null) {
                        for (View v : views) {
                            if (v != null) revokeRule(v, rule);
                        }
                    }
                    continue;
                }
                View view = ViewHelper.findViewBestMatch(activity, rule);
                Preconditions.checkNotNull(view, "revoke rule fail can't found block view");
                revokeRule(view, rule);
            } catch (NullPointerException e) {
                Logger.w(TAG, "[ViewController] revoke rule fail (act=" + activity + "): " + e.getMessage());
            }
        }
    }

    public static void revokeRule(View v, ViewRule viewRule) {
        if (v == null || viewRule == null) return;
        com.kaisar.xposed.godmode.engine.rule.ViewRule engineRule = toEngineRule(viewRule);
        if (viewRule.isModifyRule()) {
            getModifyApplier().revoke(v, engineRule);
        } else {
            getRemoveApplier().revoke(v, engineRule);
        }
    }

    private ViewController() {
    }

}
