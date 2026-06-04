package com.kaisar.xposed.godmode.engine.rule;

/**
 * 规则工厂 — 提供标准的移除规则和修改规则构造方法。
 * <p>
 * 当前为工厂骨架，具体构造逻辑仍由 ViewHelper.makeRemoveRule/makeModifyRule 提供。
 * 后续重构时可将构造逻辑迁移至此。
 */
public final class RuleFactory {

    private RuleFactory() {
    }

    /**
     * 创建一条移除规则。
     *
     * @return 新的 ViewRule 实例，ruleTag 为 null 表示移除规则
     */
    public static ViewRule makeRemoveRule() {
        ViewRule rule = new ViewRule();
        rule.ruleTag = null;
        return rule;
    }

    /**
     * 创建一条修改规则。
     *
     * @return 新的 ViewRule 实例，ruleTag 为 "modify" 表示修改规则
     */
    public static ViewRule makeModifyRule() {
        ViewRule rule = new ViewRule();
        rule.ruleTag = "modify";
        return rule;
    }

    /**
     * 判断是否为修改规则。
     */
    public static boolean isModifyRule(ViewRule rule) {
        return rule != null && rule.ruleTag != null && !rule.ruleTag.isEmpty();
    }

    /**
     * 判断是否为移除规则。
     */
    public static boolean isRemoveRule(ViewRule rule) {
        return rule != null && (rule.ruleTag == null || rule.ruleTag.isEmpty());
    }
}
