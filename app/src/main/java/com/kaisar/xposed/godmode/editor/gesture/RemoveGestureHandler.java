package com.kaisar.xposed.godmode.editor.gesture;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.orchestrator.ViewController;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.editor.action.ParticleEffectHelper;
import com.kaisar.xposed.godmode.editor.overlay.CancelView;
import com.kaisar.xposed.godmode.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.util.BitmapUtils;
import com.kaisar.xposed.godmode.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 移除手势处理 — 长按拖动移除视图：粒子动画 + 取消区域 + IPC 持久化。
 * <p>
 * 粒子动画和 IPC 持久化流程委托给 {@link ParticleEffectHelper}。
 */
public final class RemoveGestureHandler {

    private static final String TAG = "RemoveGestureHandler";
    private static final int MARK_COLOR = GmConstants.OVERLAY_COLOR_GREEN;

    private RemoveGestureHandler() {}

    /** 开始移除拖拽：创建遮罩、取消区域、应用隐藏规则并截图 */
    public static RemoveState startDrag(View v) {
        RemoveState state = new RemoveState();
        try {
            Activity activity = Preconditions.checkNotNull(
                    ViewUtils.getAttachedActivityFromView(v));
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
            state.snapshot = BitmapUtils.snapshotView(
                    ViewUtils.findTopParentViewByChildView(v));
            state.viewRule = RuleRecordFactory.makeRemoveRule(v, ModuleBootstrap.getEditorOrchestrator().isInfoFlowMode());

            state.cancelView = new CancelView(activity);
            state.cancelView.attachToContainer(container);

            state.maskView = MaskView.makeMaskView(activity);
            state.maskView.setMaskOverlay(v);
            state.maskView.setMarkColor(MARK_COLOR);
            state.maskView.updateOverlayBounds(ViewUtils.getLocationInWindow(v));
            state.maskView.attachToContainer(container);

            ViewController.getDefault().applyRule(v, state.viewRule);
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            Logger.e(TAG, "[EventHandler] startRemoveDrag fail", e);
            return null;
        }
        return state;
    }

    /** 完成移除拖拽：根据是否拖入取消区域决定撤销操作或执行粒子动画 + IPC 持久化 */
    public static void finishDrag(View v, RemoveState state, IRuleEditor ruleEditor) {
        Activity activity = ViewUtils.getAttachedActivityFromView(v);
        if (activity == null) return;

        if (state.cancelView != null) state.cancelView.detachFromContainer();

        if (state.maskView != null && state.maskView.isMarked()) {
            // 已拖入取消区域：撤销操作
            state.maskView.detachFromContainer();
            state.viewRule.visibility = View.VISIBLE;
            ViewController.getDefault().revokeRule(v, state.viewRule);
            CommonUtils.recycleNullableBitmap(state.snapshot);
        } else {
            // 未拖入取消区域：委托 ParticleEffectHelper 执行粒子动画 + IPC 持久化
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
            ParticleEffectHelper.execute(activity, state.maskView, container,
                    state.viewRule, state.snapshot,
                    v.getContext().getPackageName(),
                    state.maskView, ruleEditor, /* onComplete */ null);
        }
    }

    /** 清除拖拽状态 */
    public static void clearState(RemoveState state) {
        if (state != null) {
            state.snapshot = null;
            state.maskView = null;
            state.cancelView = null;
            state.viewRule = null;
        }
    }

    /** 移除拖拽状态容器 */
    public static final class RemoveState {
        public Bitmap snapshot;
        public RuleRecord viewRule;
        public MaskView maskView;
        public CancelView cancelView;
    }
}
