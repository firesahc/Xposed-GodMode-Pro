package com.kaisar.xposed.godmode.orchestrator;

/**
 * Repeatable 规则业务门控契约 — 解耦 orchestrator 与 inject 层的共享开关。
 * <p>
 * 历史上 orchestrator 层直接调用 inject 层注册中心的静态开关，
 * 而注册中心又反向依赖 orchestrator 做 EventBus 注册，形成双向循环。
 * 本接口由 inject 层实现、orchestrator 层持有，依赖方向只允许
 * orchestrator {@code ->} 接口、inject {@code ->} 接口实现，
 * 不允许 orchestrator 直接引用 inject 注册中心的静态方法。
 * <p>
 * 布尔语义与原 repeatable 业务门控开关完全一致：仅做业务门控，不改变物理 Hook
 * 的安装/卸载行为。
 */
public interface RepeatableRuleGate {

    /** 更新 repeatable 规则业务门控，不改变物理 Hook。 */
    void setRepeatableRulesEnabled(boolean enabled);

    /** 查询 repeatable 规则业务门控当前状态。 */
    boolean isRepeatableRulesEnabled();
}
