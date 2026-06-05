package com.kaisar.xposed.godmode.engine.event;

import java.util.Map;

/**
 * 规则变更事件。
 * 由 GodModeInjector 在收到 IPC 规则变更通知时通过 EventBus 发布。
 * <p>
 * 注：rules 字段使用原始 Map 类型而非 ActRules，
 * 避免 engine 模块对 app 模块的类型依赖。
 * ActRules extends {@code HashMap<String, List<ViewRule>>}，
 * 发布方可直接传入 ActRules 实例（自动向上转型）。
 */
public final class RulesChangedEvent {

    /** 发生规则变更的包名 */
    public final String packageName;

    /** 变更后的规则集合 */
    @SuppressWarnings("rawtypes")
    public final Map rules;

    @SuppressWarnings("rawtypes")
    public RulesChangedEvent(String packageName, Map rules) {
        this.packageName = packageName;
        this.rules = rules;
    }
}
