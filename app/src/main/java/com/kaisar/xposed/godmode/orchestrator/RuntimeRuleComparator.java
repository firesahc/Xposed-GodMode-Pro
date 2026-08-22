package com.kaisar.xposed.godmode.orchestrator;

import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 运行时内容比较器 — 供规则 diff 判定"效果是否变化"。
 * <p>
 * 比较范围 = 匹配结构语义（{@code MatchSpec#hasSameRuntimeSemantics}）+ 效果字段；
 * <b>有意排除</b>两类非运行时数据：
 * <ul>
 *   <li>{@code ruleTag} — wire 层序列化判别器，运行时 matcher/applier 不读取；</li>
 *   <li>alias/imagePath/x/y/width/height — 采集与展示数据，不影响匹配与应用。</li>
 * </ul>
 * 与 {@link RuleRecord#contentEquals} 的区别：后者面向 UI DiffUtil，
 * 额外比较展示与坐标字段。两者同名不同义，选用前先确认消费场景。
 * <p>
 * 升级触发条件：若未来 ruleTag 承载运行时语义，
 * 本类与 {@code ModifyEffect#equals} 需同步扩展比较范围。
 */
final class RuntimeRuleComparator {

    private RuntimeRuleComparator() {}

    static boolean contentEquals(RuleRecord left, RuleRecord right) {
        return left == right || left != null && right != null
                && left.getMatchSpec().hasSameRuntimeSemantics(right.getMatchSpec())
                && left.getEffect().equals(right.getEffect());
    }
}
