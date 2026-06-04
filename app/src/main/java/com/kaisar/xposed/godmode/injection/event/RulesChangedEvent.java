package com.kaisar.xposed.godmode.injection.event;

import com.kaisar.xposed.godmode.rule.ActRules;

/**
 * 规则变更事件。
 * 由 GodModeInjector 在收到 IPC 规则变更通知时通过 EventBus 发布。
 */
public final class RulesChangedEvent {

    /** 发生规则变更的包名 */
    public final String packageName;

    /** 变更后的规则集合 */
    public final ActRules rules;

    public RulesChangedEvent(String packageName, ActRules rules) {
        this.packageName = packageName;
        this.rules = rules;
    }
}
