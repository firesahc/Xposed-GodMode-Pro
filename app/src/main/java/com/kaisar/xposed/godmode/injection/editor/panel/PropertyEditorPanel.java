package com.kaisar.xposed.godmode.injection.editor.panel;

import android.view.View;

/**
 * 属性编辑面板 — 宽/高/透明度/文本/图片属性编辑。
 * 计划从 ModifyPanelController 提取 (~328行)。
 * <p>
 * 当前为骨架，待第4阶段后续迁移实际 UI 逻辑。
 */
public class PropertyEditorPanel {

    private View mPanelView;
    private View mTargetView;

    public void show(View targetView, android.app.Activity activity,
            android.view.ViewGroup container) {
        mTargetView = targetView;
        // TODO: inflate layout_modify_panel → setup seekers/inputs → attach
    }

    public void dismiss() {
        // TODO: animate out + restore view state + remove from parent
        mPanelView = null;
        mTargetView = null;
    }

    public void confirm() {
        // TODO: collect all modified values → build modify rule → IPC persist
        dismiss();
    }

    public void cancel() {
        // TODO: restore original view state → dismiss
        dismiss();
    }

    public boolean isShowing() {
        return mPanelView != null;
    }

    public View getTargetView() {
        return mTargetView;
    }
}
