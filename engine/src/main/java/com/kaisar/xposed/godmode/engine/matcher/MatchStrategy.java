package com.kaisar.xposed.godmode.engine.matcher;

import android.view.View;

import com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec;

/**
 * 匹配策略接口 — 包级私有，仅 CompositeMatcher 可见。
 * 每个实现负责一种匹配维度（深度/资源/文本/描述/RecyclerView）。
 */
interface MatchStrategy {

    /** 计算视图与规则的匹配得分（0-100），越高越匹配 */
    int computeScore(View view, RuleMatchSpec rule);

    /** 策略优先级 — 决定执行顺序，数值越大越优先 */
    default int priority() {
        return 0;
    }
}
