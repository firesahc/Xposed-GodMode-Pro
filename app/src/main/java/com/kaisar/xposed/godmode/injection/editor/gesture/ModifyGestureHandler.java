package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.view.View;
import android.view.ViewGroup;

/**
 * 修改手势处理器 — 长按拖拽修改视图位置 + 吸附对齐。
 * 计划从 EventHandlerHook 的 modify 分支提取 (~186行)。
 * <p>
 * 当前为骨架，待第4阶段后续迁移实际逻辑。
 */
public class ModifyGestureHandler {

    private View mTargetView;
    private int mOriginalLeftMargin;
    private int mOriginalTopMargin;
    private boolean mIsActive;

    public void onLongPress(View targetView) {
        mTargetView = targetView;
        mIsActive = true;
        // TODO: 记录原始 margin + 开始拖拽
        ViewGroup.MarginLayoutParams mlp =
                (ViewGroup.MarginLayoutParams) targetView.getLayoutParams();
        mOriginalLeftMargin = mlp.leftMargin;
        mOriginalTopMargin = mlp.topMargin;
    }

    public void onDrag(float dx, float dy) {
        if (!mIsActive || mTargetView == null) return;
        // TODO: 计算新 margin → 网格吸附 → 兄弟视图边缘吸附 → 更新 layoutParams
    }

    public void onDrop() {
        if (!mIsActive) return;
        mIsActive = false;
        // TODO: 计算最终偏移量 → 创建 modify 规则 → IPC 持久化
        mTargetView = null;
    }

    public void cancel() {
        mIsActive = false;
        // TODO: 恢复原始 margin
        mTargetView = null;
    }

    public boolean isActive() {
        return mIsActive;
    }
}
