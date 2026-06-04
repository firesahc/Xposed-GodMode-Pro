package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.view.View;

/**
 * 移除手势处理器 — 长按拖拽移除 + 粒子动画。
 * 计划从 EventHandlerHook 的 remove 分支提取 (~187行)。
 * <p>
 * 当前为骨架，待第4阶段后续迁移实际逻辑。
 */
public class RemoveGestureHandler {

    private View mPhantomView;
    private View mTargetView;
    private boolean mIsActive;

    public void onLongPress(View targetView) {
        mTargetView = targetView;
        mIsActive = true;
        // TODO: 创建幻影视图 + 显示取消区域 + 开始拖拽跟踪
    }

    public void onDrag(float x, float y) {
        if (!mIsActive) return;
        // TODO: 移动幻影视图 + 检测是否在取消区域内
    }

    public void onDrop(float x, float y) {
        if (!mIsActive) return;
        mIsActive = false;
        // TODO: 判断是否取消 or 确认移除 → 粒子动画 → IPC 持久化
        mPhantomView = null;
        mTargetView = null;
    }

    public void cancel() {
        mIsActive = false;
        // TODO: 移除幻影视图 + 清理状态
        mPhantomView = null;
        mTargetView = null;
    }

    public boolean isActive() {
        return mIsActive;
    }
}
