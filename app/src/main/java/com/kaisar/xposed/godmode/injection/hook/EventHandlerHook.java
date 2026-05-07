package com.kaisar.xposed.godmode.injection.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.injection.ViewHelper.TAG_GM_CMP;
import static com.kaisar.xposed.godmode.injection.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Toast;

import com.kaisar.xposed.godmode.injection.ViewController;
import com.kaisar.xposed.godmode.injection.ViewHelper;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.Property;
import com.kaisar.xposed.godmode.injection.weiget.CancelView;
import com.kaisar.xposed.godmode.injection.weiget.MaskView;
import com.kaisar.xposed.godmode.injection.weiget.ParticleView;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class EventHandlerHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

    private static final int MARK_COLOR = Color.argb(150, 139, 195, 75);

    private boolean mIsInEditMode;
    private Bitmap mSnapshot;
    private ViewRule mViewRule;
    private MaskView mMaskView;
    private CancelView mCancelView;
    private boolean mHasBlockEvent;
    private boolean mLongClick;
    private CheckForLongPress mPendingCheckForLongPress;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private volatile boolean mMultiPointLock;
    public static volatile boolean mDragging;

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (!mIsInEditMode) return;
        View view = (View) param.thisObject;
        MotionEvent event = (MotionEvent) param.args[0];
        if (!TAG_GM_CMP.equals(view.getTag())) {
            param.setResult(dispatchTouchEvent(view, event));
        }
    }

    private float mDeltaX, mDeltaY;

    private boolean dispatchTouchEvent(View v, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (mMultiPointLock) {
                Toast.makeText(v.getContext(), "不支持多点操作", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!isAttachedToActivity(v)) {
                if (!mHasBlockEvent) {
                    Toast.makeText(v.getContext(), "该控件属于悬浮窗暂不支持编辑", Toast.LENGTH_SHORT).show();
                    mHasBlockEvent = true;
                }
                return false;
            }
            mDragging = true;
            mMultiPointLock = true;
            ViewParent parent = v.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            mDeltaX = event.getRawX() - ViewHelper.getLocationInWindow(v).left;
            mDeltaY = event.getRawY() - ViewHelper.getLocationInWindow(v).top;
            mPendingCheckForLongPress = new CheckForLongPress(v);
            mHandler.postDelayed(mPendingCheckForLongPress, ViewConfiguration.getLongPressTimeout());
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (mLongClick) {
                mMaskView.updateOverlayBounds((int) (event.getRawX() - mDeltaX), (int) (event.getRawY() - mDeltaY), v.getWidth(), v.getHeight());
                mMaskView.setMarked(mCancelView.getRealBounds().intersect(mMaskView.getRealBounds()));
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            ViewParent parent = v.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            mHandler.removeCallbacks(mPendingCheckForLongPress);
            if (mLongClick) {
                performDetachMirrorView(v);
                mLongClick = false;
            } else if (action == MotionEvent.ACTION_UP && DispatchKeyEventHook.mKeySelecting) {
                DispatchKeyEventHook.selectViewByTap(v);
            }
            mHasBlockEvent = false;
            mMultiPointLock = false;
            mDragging = false;
        }
        return true;
    }

    private boolean isAttachedToActivity(View v) {
        Object viewRootImpl = ViewHelper.findViewRootImplByChildView(v.getParent());
        if (viewRootImpl == null) return false;
        try {
            WindowManager.LayoutParams wl = (WindowManager.LayoutParams) XposedHelpers.getObjectField(viewRootImpl, "mWindowAttributes");
            return wl != null && wl.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
        } catch (Exception e) {
            return v.getWindowToken() != null;
        }
    }

    private void performAttachMirrorView(View v) {
        try {
            Activity activity = Preconditions.checkNotNull(ViewHelper.getAttachedActivityFromView(v));
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();

            mCancelView = new CancelView(activity);
            mCancelView.attachToContainer(container);

            mMaskView = MaskView.makeMaskView(activity);
            mMaskView.setMaskOverlay(v);
            mMaskView.setMarkColor(MARK_COLOR);
            mMaskView.updateOverlayBounds(ViewHelper.getLocationInWindow(v));
            mMaskView.attachToContainer(container);

            mSnapshot = ViewHelper.snapshotView(ViewHelper.findTopParentViewByChildView(v));
            mViewRule = ViewHelper.makeRule(v);
            ViewController.applyRule(v, mViewRule);
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            e.printStackTrace();
        }
    }

    private void performDetachMirrorView(final View v) {
        Activity activity = ViewHelper.getAttachedActivityFromView(v);
        try {
            Preconditions.checkNotNull(activity);
        } catch (NullPointerException e) {
            return;
        }

        mCancelView.detachFromContainer();
        if (mMaskView.isMarked()) {
            try {
                mMaskView.detachFromContainer();
                mViewRule.visibility = View.VISIBLE;
                ViewController.revokeRule(v, mViewRule);
                recycleNullableBitmap(mSnapshot);
            } finally {
                mSnapshot = null;
                mMaskView = null;
                mCancelView = null;
                mViewRule = null;
            }
        } else {
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
            final ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(1000);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    mViewRule.visibility = View.GONE;
                    ViewController.applyRule(v, mViewRule);
                    GodModeManager.getDefault().writeRule(v.getContext().getPackageName(), mViewRule, mSnapshot);
                    recycleNullableBitmap(mSnapshot);
                    mMaskView.detachFromContainer();
                }

                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    try {
                        particleView.detachFromContainer();
                    } finally {
                        mSnapshot = null;
                        mMaskView = null;
                        mCancelView = null;
                        mViewRule = null;
                    }
                }
            });
            particleView.boom(mMaskView);
        }
    }

    @Override
    public void onPropertyChange(Boolean enable) {
        mIsInEditMode = enable;
    }

    private class CheckForLongPress implements Runnable {

        private final WeakReference<View> viewRef;

        private CheckForLongPress(View view) {
            this.viewRef = new WeakReference<>(view);
        }

        @Override
        public void run() {
            View view = viewRef.get();
            Logger.d(TAG, "view =" + view);
            if (view != null) {
                Logger.d(TAG, "perform attach mirror view");
                performAttachMirrorView(view);
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                mLongClick = true;
            }
        }
    }
}
