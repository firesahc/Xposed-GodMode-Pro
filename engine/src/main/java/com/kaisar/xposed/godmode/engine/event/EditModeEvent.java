package com.kaisar.xposed.godmode.engine.event;

/**
 * 编辑模式变更事件。
 * 由 GodModeInjector 在收到 IPC 编辑模式变更通知时通过 EventBus 发布。
 */
public final class EditModeEvent {

    /** 编辑模式是否已启用 */
    public final boolean enabled;

    public EditModeEvent(boolean enabled) {
        this.enabled = enabled;
    }
}
