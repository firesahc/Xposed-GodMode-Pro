package com.kaisar.xposed.godmode.injection;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.engine.matcher.Matcher;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;

/**
 * ViewController 向后兼容委托 — 委托到 {@link com.kaisar.xposed.godmode.runtime.ViewController}。
 * <p>
 * 此文件是旧 {@code injection/ViewController.java} 迁移到 {@code runtime/} 后的过渡 stubs。
 * 所有公共 API 通过委托转发到新位置的实现。
 * <p>
 *
 * @deprecated 请使用 {@link com.kaisar.xposed.godmode.runtime.ViewController}。
 * 将在后续清理中移除。
 */
@Deprecated
public final class ViewController {

    private final com.kaisar.xposed.godmode.runtime.ViewController mDelegate;

    // =========================================================================
    // 静态委托 — 转发到 runtime.ViewController
    // =========================================================================

    /**
     * @deprecated 委托到 {@link com.kaisar.xposed.godmode.runtime.ViewController#getDefault()}
     */
    @Deprecated
    public static ViewController getDefault() {
        // 保持与旧调用方兼容：返回包装 runtime.ViewController 的 injection.ViewController 实例
        com.kaisar.xposed.godmode.runtime.ViewController real =
                com.kaisar.xposed.godmode.runtime.ViewController.getDefault();
        return new ViewController(real);
    }

    private ViewController(com.kaisar.xposed.godmode.runtime.ViewController delegate) {
        this.mDelegate = delegate;
    }

    /**
     * @deprecated 请使用 {@link com.kaisar.xposed.godmode.runtime.ViewController#ViewController(Activity)}
     */
    @Deprecated
    public ViewController(Activity activity) {
        mDelegate = new com.kaisar.xposed.godmode.runtime.ViewController(activity);
    }

    // =========================================================================
    // 实例方法委托
    // =========================================================================

    /** @deprecated 委托到 {@code runtime.ViewController#clearBlockedCache()} */
    @Deprecated
    public void clearBlockedCache() {
        mDelegate.clearBlockedCache();
    }

    /** @deprecated 委托到 {@code runtime.ViewController#applyRuleBatch(Activity, List)} */
    @Deprecated
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules) {
        mDelegate.applyRuleBatch(activity, rules);
    }

    /** @deprecated 委托到 {@code runtime.ViewController#applyRuleBatch(Activity, List, Runnable)} */
    @Deprecated
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules, Runnable onComplete) {
        mDelegate.applyRuleBatch(activity, rules, onComplete);
    }

    /** @deprecated 委托到 {@code runtime.ViewController#applyRule(View, RuleRecord)} */
    @Deprecated
    public boolean applyRule(View v, RuleRecord viewRule) {
        return mDelegate.applyRule(v, viewRule);
    }

    /** @deprecated 委托到 {@code runtime.ViewController#revokeRuleBatch(Activity, List)} */
    @Deprecated
    public void revokeRuleBatch(Activity activity, List<RuleRecord> rules) {
        mDelegate.revokeRuleBatch(activity, rules);
    }

    /** @deprecated 委托到 {@code runtime.ViewController#revokeRule(View, RuleRecord)} */
    @Deprecated
    public void revokeRule(View v, RuleRecord viewRule) {
        mDelegate.revokeRule(v, viewRule);
    }

    /** @deprecated 委托到 {@code runtime.ViewController#revokeAllRules(View)} */
    @Deprecated
    public void revokeAllRules(View root) {
        mDelegate.revokeAllRules(root);
    }

    /** @deprecated 委托到 {@code runtime.ViewController#getActivityClassName()} */
    @Deprecated
    public String getActivityClassName() {
        return mDelegate.getActivityClassName();
    }

    /**
     * 获取 Matcher 实例，委托到 runtime.ViewController。
     */
    public Matcher getMatcher() {
        return mDelegate.getMatcher();
    }
}
