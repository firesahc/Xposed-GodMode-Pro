package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.graphics.Rect;
import android.view.View;

import com.kaisar.xposed.godmode.injection.editor.ViewRuleFactory;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ViewRule;

/**
 * 预览操作处理器 — 在确认移除前临时隐藏视图。
 * <p>
 * 管理 {@code KeyInterceptor} 中的预览状态（mPreviewView、mPreviewRule、mIsPreviewing），
 * 封装 {@link #startPreview} / {@link #restorePreview} 逻辑。
 * <p>
 * 调用方负责按钮状态更新（{@code updatePreviewButton}）及
 * {@link MaskView} 与 {@code NodeSelectorPanel} 的交互。
 */
public final class PreviewHandler {

    private View mPreviewView;
    private ViewRule mPreviewRule;
    private boolean mIsPreviewing;

    /** 当前是否处于预览状态。 */
    public boolean isPreviewing() {
        return mIsPreviewing;
    }

    /**
     * 开始预览：为选中视图创建移除规则并应用（visibility = GONE）。
     *
     * @param view          被选中的目标视图
     * @param maskView      MaskView（用于清除高亮边界）
     * @param onStateChanged 状态变更通知（调用方用于更新按钮 UI）
     */
    public void startPreview(View view, MaskView maskView, Runnable onStateChanged) {
        if (view == null) return;
        try {
            mPreviewRule = ViewRuleFactory.makeRemoveRule(view);
            mPreviewRule.visibility = View.GONE;
            ViewController.getDefault().applyRule(view, mPreviewRule);
            mPreviewView = view;
            mIsPreviewing = true;
            if (onStateChanged != null) onStateChanged.run();
            if (maskView != null) maskView.updateOverlayBounds(new Rect());
        } catch (Exception e) {
            Logger.e("PreviewHandler", "startPreview fail", e);
        }
    }

    /**
     * 恢复预览：撤销移除规则（visibility = VISIBLE），更新 MaskView 高亮。
     *
     * @param maskView       MaskView（用于恢复后更新高亮边界）
     * @param selectedView   当前选中的视图（用于恢复后更新高亮边界；可能为 null）
     * @param onStateChanged 状态变更通知（调用方用于更新按钮 UI）
     */
    public void restorePreview(MaskView maskView, View selectedView, Runnable onStateChanged) {
        if (mPreviewView != null && mPreviewRule != null) {
            mPreviewRule.visibility = View.VISIBLE;
            ViewController.getDefault().revokeRule(mPreviewView, mPreviewRule);
            mPreviewView = null;
            mPreviewRule = null;
        }
        mIsPreviewing = false;
        if (onStateChanged != null) onStateChanged.run();
        if (maskView != null && selectedView != null) {
            maskView.updateOverlayBounds(ViewUtils.getLocationInWindow(selectedView));
        }
    }
}
