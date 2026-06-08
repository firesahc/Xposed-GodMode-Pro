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
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.editor.overlay.CancelView;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * 缂佸顭峰▍搴ㄥ箥鐎ｎ亜鈼㈠璺哄閹﹪宕?闁?闂傗偓閹稿骸鐦婚柟閿嬬墬鐎氳法绮旀繝姘彑 + 缂侇喗甯掗悺娆撴偉閸℃瑥浠柛鏂诲妿閺?+ IPC 闁归晲妞掔粻娆撳礌閺嶃儮鍋?
 * 濞?EventHandlerHook 闁圭粯鍔曡ぐ鍥儍閸曨厜鈺呮⒔閵堝枺浣割嚕韫囧孩鍞夊ù婊勫浮閳ь剚妲掔欢顐﹀Υ?
 */
public final class RemoveGestureHandler {

    private static final String TAG = "GodMode";
    private static final int MARK_COLOR = Color.argb(150, 139, 195, 75);

    private RemoveGestureHandler() {}

    /** 闁告帗绻傞～鎰板礌閺嶎倣鈺呮⒔閵堝棗鐝涢柟閿嬫灮缁变即宕楃€ｎ喗鐣烽悷娆忔濞存ɑ绋夊ú顏冪磿缂傚啠鏅槐婵嬪及閸撗佷粵闁告瑦鐗楃粔鐑藉礌閸濆嫮鍘?*/
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

    /** 閻庣懓鏈崹姘辩矓婵犳碍鐝熼柟閿嬬墬鐎氬潡鏁嶅顒€鍤掗柛娆愮墬缁夌兘宕氬▎鎺旂闁告鍣︾槐婵嗩啅閼碱兘鈧鎷嬮妶鍛仧缂侇喗甯掗悺娆撳礉閵娧勬毎 闁?IPC 闁归晲妞掔粻娆撳礌?*/
    public static void finishDrag(View v, RemoveState state) {
        Activity activity = ViewUtils.getAttachedActivityFromView(v);
        if (activity == null) return;

        if (state.cancelView != null) state.cancelView.detachFromContainer();

        if (state.maskView != null && state.maskView.isMarked()) {
            // 鐎瑰憡褰冭ぐ鍥р槈?
            state.maskView.detachFromContainer();
            state.viewRule.visibility = View.VISIBLE;
            ViewController.getDefault().revokeRule(v, state.viewRule);
            CommonUtils.recycleNullableBitmap(state.snapshot);
        } else {
            // 鐎规瓕灏欓垾妯兼媼閵堝繒绐楃紒顔藉笒閻℃瑩鎮ラ崱娆忎划 闁?IPC 闁归晲妞掔粻娆撳礌?
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
                            GodModeManager.getDefault().writeRule(
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

    /** 婵炴挸鎳愰幃濠勭矓婵犳碍鐝熼柣妯垮煐閳?*/
    public static void clearState(RemoveState state) {
        if (state != null) {
            state.snapshot = null;
            state.maskView = null;
            state.cancelView = null;
            state.viewRule = null;
        }
    }

    /** 缂佸顭峰▍搴∥熼垾宕囩闁绘鍩栭埀顑跨椤旀劙宕?*/
    public static final class RemoveState {
        public Bitmap snapshot;
        public RuleRecord viewRule;
        public MaskView maskView;
        public CancelView cancelView;
    }
}
