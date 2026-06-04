package com.kaisar.xposed.godmode.injection.editor;

/**
 * 编辑器状态枚举。
 */
public enum EditorState {
    /** 无编辑模式 — 正常应用交互 */
    MODE_INITIAL,
    /** 移除模式 — 点击选中 → 长按拖拽 → 松手删除 + 粒子动画 */
    MODE_REMOVE,
    /** 修改模式 — 点击选中 → 长按拖拽 → 网格/边缘吸附 → 保存位置偏移 */
    MODE_MODIFY
}
