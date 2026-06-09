package com.kaisar.xposed.godmode.engine.applier;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.ModifySpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

/**
 * 规则应用器接口 — 将匹配到的规则应用到具体 View 或撤销。
 * <p>
 * 新代码应优先使用接受 {@link ModifySpec} 的方法。
 */
public interface RuleApplier {

    // ===== 新 API：使用纯 ModifySpec =====

    /** 应用规则到视图，返回 true 表示实际生效 */
    boolean apply(View view, ModifySpec spec);

    /** 撤销规则对视图的修改 */
    boolean revoke(View view, ModifySpec spec);

    // ===== 旧 API：使用 RuleMatchSpec（内部转为 ModifySpec 后委托） =====

    /** @deprecated 改为调用 {@link #apply(View, ModifySpec)} */
    @Deprecated
    default boolean apply(View view, RuleMatchSpec rule) {
        return apply(view, rule != null ? rule.getModifySpec() : null);
    }

    /** @deprecated 改为调用 {@link #revoke(View, ModifySpec)} */
    @Deprecated
    default boolean revoke(View view, RuleMatchSpec rule) {
        return revoke(view, rule != null ? rule.getModifySpec() : null);
    }

    /** 清空所有缓存状态（Activity onDestroy 时调用） */
    void clearCache();
}
