package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.applier.ModifyApplier;
import com.kaisar.xposed.godmode.engine.applier.RemoveApplier;
import com.kaisar.xposed.godmode.engine.applier.RuleApplier;
import com.kaisar.xposed.godmode.engine.matcher.CompositeMatcher;
import com.kaisar.xposed.godmode.engine.matcher.IMatcher;
import com.kaisar.xposed.godmode.engine.rule.ActionSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMapper;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;

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

    /** 批量应用规则 */
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules) {
        int appliedCount = 0;
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
                            if (v != null && applyRule(v, rule)) appliedCount++;
                        }
                    }
                    continue;
                }
                // 非 repeatable：策略链匹配（depth → resourceId → 全树兜底）
                View view = getMatcher().matchView(decorView, engineRule.getMatchSpec());
                if (view == null) {
                    Logger.w(TAG, "[ViewController] Failed: " + activity + "#" + rule.viewClass
                            + " block failed: not match any view");
                    continue;
                }
                if (applyRule(view, rule)) appliedCount++;
            } catch (Exception e) {
                Logger.w(TAG, "[ViewController] apply rule failed", e);
            }
        }
        if (appliedCount > 0) {
            Logger.d(TAG, "[ViewController] applied " + appliedCount + " rules for " + activity);
        }
    }

    /** 应用单条规则 */
    public boolean applyRule(View v, RuleRecord viewRule) {
        if (v == null || viewRule == null) return false;
        RuleMatchSpec engineRule = toEngineRule(viewRule);
        ActionSpec spec = engineRule.getActionSpec();
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
        if (viewRule.isModifyRule()) {
            getModifyApplier().revoke(v, spec);
        } else {
            getRemoveApplier().revoke(v, spec);
        }
    }
}
