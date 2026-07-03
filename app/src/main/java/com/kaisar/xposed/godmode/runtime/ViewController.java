package com.kaisar.xposed.godmode.runtime;

import android.app.Activity;
import android.view.View;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.List;

/**
 * ViewController 运行时代理 — 委托到 {@link com.kaisar.xposed.godmode.injection.ViewController}。
 * <p>
 * 此文件是 {@code injection/ViewController.java} 迁移到 {@code runtime/} 的过渡步骤。
 * 所有公共 API 通过委托转发到旧位置的实现。
 * <p>
 *
 * @deprecated 请直接使用 {@link com.kaisar.xposed.godmode.injection.ViewController}。
 * 此类将在 Phase 6 清理。
 */
@Deprecated
public final class ViewController {

    private static final String TAG = "ViewController.Proxy";

    private final com.kaisar.xposed.godmode.injection.ViewController mDelegate;

    // =========================================================================
    // 静态代理 — 委托到 injection.ViewController
    // =========================================================================

    /**
     * @deprecated 委托到 {@link com.kaisar.xposed.godmode.injection.ViewController#getDefault()}
     */
    @Deprecated
    public static com.kaisar.xposed.godmode.injection.ViewController getDefault() {
        return com.kaisar.xposed.godmode.injection.ViewController.getDefault();
    }

    // =========================================================================
    // 实例构造
    // =========================================================================

    /**
     * @deprecated 请使用 {@link com.kaisar.xposed.godmode.injection.ViewController#ViewController(Activity)}
     */
    @Deprecated
    public ViewController(Activity activity) {
        mDelegate = new com.kaisar.xposed.godmode.injection.ViewController(activity);
    }

    // =========================================================================
    // 实例方法委托
    // =========================================================================

    /** @deprecated 委托到 {@code injection.ViewController#clearBlockedCache()} */
    @Deprecated
    public void clearBlockedCache() {
        mDelegate.clearBlockedCache();
    }

    /** @deprecated 委托到 {@code injection.ViewController#applyRuleBatch(Activity, List)} */
    @Deprecated
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules) {
        mDelegate.applyRuleBatch(activity, rules);
    }

    /** @deprecated 委托到 {@code injection.ViewController#applyRuleBatch(Activity, List, Runnable)} */
    @Deprecated
    public void applyRuleBatch(Activity activity, List<RuleRecord> rules, Runnable onComplete) {
        mDelegate.applyRuleBatch(activity, rules, onComplete);
    }

    /** @deprecated 委托到 {@code injection.ViewController#applyRule(View, RuleRecord)} */
    @Deprecated
    public boolean applyRule(View v, RuleRecord viewRule) {
        return mDelegate.applyRule(v, viewRule);
    }

    /** @deprecated 委托到 {@code injection.ViewController#revokeRuleBatch(Activity, List)} */
    @Deprecated
    public void revokeRuleBatch(Activity activity, List<RuleRecord> rules) {
        mDelegate.revokeRuleBatch(activity, rules);
    }

    /** @deprecated 委托到 {@code injection.ViewController#revokeRule(View, RuleRecord)} */
    @Deprecated
    public void revokeRule(View v, RuleRecord viewRule) {
        mDelegate.revokeRule(v, viewRule);
    }

    /** @deprecated 委托到 {@code injection.ViewController#revokeAllRules(View)} */
    @Deprecated
    public void revokeAllRules(View root) {
        mDelegate.revokeAllRules(root);
    }

    /** @deprecated 委托到 {@code injection.ViewController#getActivityClassName()} */
    @Deprecated
    public String getActivityClassName() {
        return mDelegate.getActivityClassName();
    }
}
