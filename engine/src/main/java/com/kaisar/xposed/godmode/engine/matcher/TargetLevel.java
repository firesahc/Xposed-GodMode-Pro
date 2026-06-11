package com.kaisar.xposed.godmode.engine.matcher;

/**
 * 匹配目标层级 — 控制信息流（repeatable）规则匹配返回什么级别的视图。
 * <p>
 * 序列化方式同 {@link MatchMode}：JSON 自动 name() / valueOf()，Parcel 手动 name() / valueOf()。
 */
public enum TargetLevel {

    /**
     * 匹配卡片内部的具体子元素（当前行为）。
     * 通过 itemPath 从卡片根导航到内部目标视图（Button/TextView 等）。
     */
    ELEMENT,

    /**
     * 匹配整个内容卡片根视图。
     * 不导航 itemPath，直接验证并返回卡片根（RecyclerView 的直接子 View）。
     * 修改模式下仍需通过 itemPath 导航到内部元素执行修改。
     */
    CARD
}
