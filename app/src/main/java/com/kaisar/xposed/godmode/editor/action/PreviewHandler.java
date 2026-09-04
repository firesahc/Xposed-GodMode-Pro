package com.kaisar.xposed.godmode.editor.action;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.orchestrator.RuleLifecycleManager;
import com.kaisar.xposed.godmode.orchestrator.ViewController;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.rule.RemoveEffect;

/**
 * 预览处理器 — 通过临时隐藏视图来预览屏蔽效果。
 * <p>
 * 配合 {@code KeyInterceptor} 维护三个状态：PreviewView、PreviewRule、IsPreviewing。
 * 通过 {@link #startPreview} / {@link #restorePreview} 控制预览开关。
 * <p>
 * 调用方负责在 UI 上反映预览状态（如 {@code updatePreviewButton}），
 * 以及管理 {@link MaskView} 和 {@code NodeSelectorPanel} 的联动。
 */
public final class PreviewHandler {

    private static final String TAG = "PreviewHandler";

    private View mPreviewView;
    private RuleRecord mPreviewRule;
    private boolean mIsPreviewing;

    /** 检查是否正在预览中 */
    public boolean isPreviewing() {
        return mIsPreviewing;
    }

    /**
     * 按 Activity 解析作用域控制器：优先 Activity 级实例（applier 缓存隔离），
     * 取不到时回落进程单例（与修复前行为逐字一致）。
     */
    private static ViewController resolveController(Activity activity) {
        if (activity != null) {
            try {
                ViewController scoped =
                        RuleLifecycleManager.getInstance().getViewController(activity);
                if (scoped != null) return scoped;
            } catch (Exception e) {
                Logger.w(TAG, "resolve scoped controller failed, fallback to default", e);
            }
        }
        return ViewController.getDefault();
    }

    /**
     * 开始预览：通过临时设置视图 visibility = GONE 来隐藏目标视图。
     *
     * @param activity       预览所在 Activity（可 null，为 null 时回落进程单例）
     * @param view           要隐藏的目标视图
     * @param maskView       MaskView 用于清除高亮遮罩
     * @param onStateChanged 预览状态变化回调，用于更新 UI
     * @param infoFlowMode   当前信息流模式，由调用方在触发时显式传入
     */
    public void startPreview(Activity activity, View view, MaskView maskView,
            Runnable onStateChanged, boolean infoFlowMode) {
        if (view == null) return;
        try {
            mPreviewRule = RuleRecordFactory.makeRemoveRule(view, infoFlowMode);
            mPreviewRule = mPreviewRule.withEffect(RemoveEffect.of(View.GONE));
            resolveController(activity).applyRule(view, mPreviewRule);
            mPreviewView = view;
            mIsPreviewing = true;
            if (onStateChanged != null) onStateChanged.run();
            if (maskView != null) maskView.updateOverlayBounds(new Rect());
        } catch (Exception e) {
            Logger.e(TAG, "startPreview fail", e);
        }
    }

    /**
     * 恢复预览：将视图 visibility 恢复为 VISIBLE，并清除预览状态。同时恢复 MaskView 高亮。
     * <p>
     * 必须使用与 {@link #startPreview} 同一 Activity 解析出的控制器，否则
     * apply 与 revoke 落到不同 applier 实例，基线所有权互踩。
     *
     * @param activity       预览所在 Activity（可 null，为 null 时回落进程单例）
     * @param maskView       MaskView 用于重新绘制选中框（可传 null）
     * @param selectedView   当前选中的视图，用于更新 MaskView 位置（可传 null）
     * @param onStateChanged 预览状态变化回调，用于更新 UI
     */
    public void restorePreview(Activity activity, MaskView maskView, View selectedView,
            Runnable onStateChanged) {
        if (mPreviewView != null && mPreviewRule != null) {
            resolveController(activity).revokeRule(mPreviewView, mPreviewRule);
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
