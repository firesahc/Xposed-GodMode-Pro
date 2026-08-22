package com.kaisar.xposed.godmode.engine.matcher;

/**
 * 匹配模式 — 控制匹配策略中字符串比较的方式。
 * <p>
 * 默认使用 {@link #EXACT}，与旧版行为一致。
 * 宽松模式（CONTAINS / STARTS_WITH / ENDS_WITH）适用于动态文本场景，
 * REGEX 模式适用于需要灵活模式匹配的情况。
 */
public enum MatchMode {

    /** 精确匹配（默认），使用 TextUtils.equals / String.equals */
    EXACT,

    /** 包含匹配，target.contains(value) */
    CONTAINS,

    /** 前缀匹配，target.startsWith(value) */
    STARTS_WITH,

    /** 后缀匹配，target.endsWith(value) */
    ENDS_WITH,

    /** 正则表达式匹配，Pattern.matches(value, target) */
    REGEX;

    /**
     * 宽松解析 — 未知或缺失名称返回 null（语义等同默认 EXACT，
     * 见 {@link com.kaisar.xposed.godmode.engine.rule.MatchFields#getMatchMode} 契约）。
     * 与 RuleRecordTypeAdapter 的 JSON 容错读取语义对称；供 Parcel 反序列化等 wire 入口复用。
     */
    public static MatchMode fromName(String name) {
        if (name == null) return null;
        for (MatchMode mode : values()) {
            if (mode.name().equals(name)) return mode;
        }
        return null;
    }

}
