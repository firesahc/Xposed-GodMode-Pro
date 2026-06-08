package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.animation.Animator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.editor.RuleRecordFactory;
import com.kaisar.xposed.godmode.injection.util.BitmapUtils;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.editor.overlay.CancelView;
import com.kaisar.xposed.godmode.injection.editor.overlay.MaskView;
import com.kaisar.xposed.godmode.injection.editor.overlay.ParticleView;
import com.kaisar.xposed.godmode.injection.util.TaskExecutor;
import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;

import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.util.Preconditions;

/**
 * 缁夊娅庨幍瀣◢婢跺嫮鎮婇崳?閳?闂€鎸庡瘻閹锋牗瀚跨粔濠氭珟 + 缁帒鐡欓悥鍡欏仮閸斻劎鏁?+ IPC 閹镐椒绠欓崠鏍モ偓?
 * 娴?EventHandlerHook 閹绘劕褰囬惃鍕╅梽銈喣佸蹇庢唉娴滄帡鈧槒绶妴?
 */
public final class RemoveGestureHandler {

    private static final String TAG = "GodMode";
    private static final int MARK_COLOR = Color.argb(150, 139, 195, 75);

    private RemoveGestureHandler() {}

    /** 閸掓繂顫愰崠鏍╅梽銈嗗珛閹锋枻绱伴崗瀣畷鐟欏棗娴樻稉娲紕缂冣晪绱濋弰鍓с仛閸欐牗绉烽崠鍝勭厵 */
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

    /** 鐎瑰本鍨氱粔濠氭珟閹锋牗瀚块敍姘嚒閸欐牗绉烽崚娆掔箷閸樼噦绱濆鑼€樼拋銈呭灟缁帒鐡欓崝銊ф暰 閳?IPC 閹镐椒绠欓崠?*/
    public static void finishDrag(View v, RemoveState state) {
        Activity activity = ViewUtils.getAttachedActivityFromView(v);
        if (activity == null) return;

        if (state.cancelView != null) state.cancelView.detachFromContainer();

        if (state.maskView != null && state.maskView.isMarked()) {
            // 瀹告彃褰囧☉?
            state.maskView.detachFromContainer();
            state.viewRule.visibility = View.VISIBLE;
            ViewController.getDefault().revokeRule(v, state.viewRule);
            CommonUtils.recycleNullableBitmap(state.snapshot);
        } else {
            // 瀹歌尙鈥樼拋銈忕窗缁帒鐡欓悥鍡欏仮 閳?IPC 閹镐椒绠欓崠?
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

    /** 濞撳懐鎮婄粔濠氭珟閻樿埖鈧?*/
    public static void clearState(RemoveState state) {
        if (state != null) {
            state.snapshot = null;
            state.maskView = null;
            state.cancelView = null;
            state.viewRule = null;
        }
    }

    /** 缁夊娅庡Ο鈥崇础閻樿埖鈧礁顔愰崳?*/
    public static final class RemoveState {
        public Bitmap snapshot;
        public RuleRecord viewRule;
        public MaskView maskView;
        public CancelView cancelView;
    }
}
