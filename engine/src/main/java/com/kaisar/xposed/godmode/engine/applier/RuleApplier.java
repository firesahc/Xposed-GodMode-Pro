package com.kaisar.xposed.godmode.engine.applier;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.RuleEffect;

/**
 * 规则应用器接口 — 将匹配到的规则应用到具体 View 或撤销。
 * <p>
 * 实现类：{@link ModifyApplier}（修改）、{@link RemoveApplier}（移除）。
 */
public interface RuleApplier<E extends RuleEffect> {

    /** 应用规则到视图，返回 true 表示实际生效 */
    boolean apply(View view, E effect);

    /** 撤销规则对视图的修改 */
    boolean revoke(View view, E effect);

    /** 清空所有缓存状态（Activity onDestroy 时调用） */
    void clearCache();
}
