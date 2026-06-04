package com.kaisar.xposed.godmode.injection.editor;

import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.Property;

/**
 * 编辑模式管理器 — 统一编辑模式状态切换逻辑。
 * <p>
 * 封装当前散落在 GodModeInjector、GodModeHelper、QuickSettingsService
 * 中的编辑模式开/关判断和切换操作。
 */
public final class EditModeManager {

    private final Property<Boolean> mSwitchProp;

    public EditModeManager(Property<Boolean> switchProp) {
        this.mSwitchProp = switchProp;
    }

    /** 当前是否处于编辑模式 */
    public boolean isInEditMode() {
        return mSwitchProp.get();
    }

    /** 开启编辑模式（默认进入移除模式） */
    public void enterEditMode() {
        mSwitchProp.set(true);
    }

    /** 关闭编辑模式 */
    public void exitEditMode() {
        mSwitchProp.set(false);
    }

    /** 通过 IPC 持久化编辑模式状态 */
    public void persistEditMode(boolean enable) {
        GodModeManager.getDefault().setEditMode(enable);
    }

    /** 切换编辑模式并持久化 */
    public void toggleEditMode() {
        boolean current = isInEditMode();
        boolean next = !current;
        persistEditMode(next);
        enterEditMode();
    }
}
