package com.kaisar.xposed.godmode.engine.event;

import java.util.Map;

/**
 * 规则变更事件。
 * 由 GodModeInjector 在收到 IPC 规则变更通知时通过 EventBus 发布。
 * <p>
 * rules 字段声明为 {@code Map<String, ?>} 而非 ActRules，
 * 避免 engine 模块对 app 模块的类型依赖。
 * 消费方（app 模块）在订阅方法中通过转型获取实际类型。
 */
public final class RulesChangedEvent {

    /** 发生规则变更的包名 */
    public final String packageName;

    /** 变更后的规则集合（实际类型为 ActRules，消费方转型获取） */
    public final Map<String, ?> rules;

    public RulesChangedEvent(String packageName, Map<String, ?> rules) {
        this.packageName = packageName;
        this.rules = rules;
    }
}
