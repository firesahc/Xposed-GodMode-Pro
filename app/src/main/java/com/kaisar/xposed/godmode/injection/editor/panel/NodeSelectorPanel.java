package com.kaisar.xposed.godmode.injection.editor.panel;

import android.view.View;

/**
 * 节点选择面板 — 视图树导航 + 选中/预览/移除/修改操作。
 * 计划从 DispatchKeyEventHook 提取 (SeekBar + 上下按钮交互)。
 * <p>
 * 当前为骨架，待第4阶段后续迁移实际 UI 逻辑。
 */
public class NodeSelectorPanel {

    private View mPanelView;
    private int mCurrentIndex;
    private java.util.List<java.lang.ref.WeakReference<View>> mViewNodes;

    public void show(java.util.List<java.lang.ref.WeakReference<View>> viewNodes) {
        mViewNodes = viewNodes;
        mCurrentIndex = 0;
        // TODO: inflate panel layout → setup SeekBar/buttons → attach to DecorView
    }

    public void dismiss() {
        // TODO: animate out + remove from parent
        mPanelView = null;
        mViewNodes = null;
    }

    public View getSelectedView() {
        if (mViewNodes != null && mCurrentIndex < mViewNodes.size()) {
            java.lang.ref.WeakReference<View> ref = mViewNodes.get(mCurrentIndex);
            return ref != null ? ref.get() : null;
        }
        return null;
    }

    public void selectNext() {
        if (mViewNodes != null && mCurrentIndex < mViewNodes.size() - 1) {
            mCurrentIndex++;
            // TODO: update mask overlay bounds
        }
    }

    public void selectPrevious() {
        if (mCurrentIndex > 0) {
            mCurrentIndex--;
            // TODO: update mask overlay bounds
        }
    }

    public boolean isShowing() {
        return mPanelView != null;
    }
}
