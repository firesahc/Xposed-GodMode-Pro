package com.kaisar.xposed.godmode.orchestrator;

import com.kaisar.xposed.godmode.rule.RuleRecord;

/** Runtime equality is limited to matcher semantics and effect values. */
final class RuntimeRuleComparator {

    private RuntimeRuleComparator() {}

    static boolean contentEquals(RuleRecord left, RuleRecord right) {
        return left == right || left != null && right != null
                && left.getMatchSpec().hasSameRuntimeSemantics(right.getMatchSpec())
                && left.getEffect().equals(right.getEffect());
    }
}
