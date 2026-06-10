package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.animation.Animator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.editor.overlay.CancelView;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 移除手势处理 — 长按拖动移除视图：粒子动画 + 取消区域 + IPC 持久化。
 * 由 EventHandlerHook 提取的移除模式交互逻辑。
 */
public final class RemoveGestureHandler {

    private static final String TAG = "RemoveGestureHandler";
    private static final int MARK_COLOR = Color.argb(150, 139, 195, 75);

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
            state.viewRule = RuleRecordFactory.makeRemoveRule(v);

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
    public static void finishDrag(View v, RemoveState state) {
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
            // 未拖入取消区域：执行粒子动画并保存规则到 IPC
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
            ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(1000);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    state.viewRule.visibility = View.GONE;
                    ViewController.getDefault().applyRule(v, state.viewRule);
                    BitmapUtils.drawRuleMask(state.snapshot, state.viewRule);
                    state.maskView.detachFromContainer();
                    TaskExecutor.executeIo(() -> {
                        try {
                            RuleServiceClient.getDefault().writeRule(
                                    v.getContext().getPackageName(),
                                    state.viewRule, state.snapshot);
                        } catch (Exception e) {
                            Logger.e(TAG, "[EventHandler] write rule fail", e);
                        }
                        CommonUtils.recycleNullableBitmap(state.snapshot);
                    });
                }
                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    particleView.detachFromContainer();
                }
            });
            particleView.boom(state.maskView);
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
