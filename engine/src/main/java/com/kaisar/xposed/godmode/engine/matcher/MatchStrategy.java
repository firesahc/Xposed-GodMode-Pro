package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.MatchSpec;
import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

/**
 * 匹配策略接口 — 包级私有，仅 CompositeMatcher 可见。
 * 每个实现负责一种匹配维度（深度/资源/文本/描述/RecyclerView）。
 * <p>
 * 新策略应实现 {@link #computeScore(View, MatchSpec)} 方法；
 * 旧版 {@link #computeScore(View, RuleMatchSpec)} 已有默认委托。
 */
interface MatchStrategy {

    /** 计算视图与规则的匹配得分（0-100），越高越匹配 */
    int computeScore(View view, MatchSpec spec);

    /** 策略优先级 — 决定执行顺序，数值越大越优先 */
    default int priority() {
        return 0;
    }

    /**
     * 旧版评分方法 — 默认委托给 MatchSpec 版本。
     * 仅当策略需要访问 RuleMatchSpec 中非匹配字段时才需覆写此方法。
     *
     * @deprecated 改为实现 {@link #computeScore(View, MatchSpec)}
     */
    @Deprecated
    default int computeScore(View view, RuleMatchSpec rule) {
        return computeScore(view, rule != null ? rule.getMatchSpec() : null);
    }
}
