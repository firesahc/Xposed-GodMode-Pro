package com.kaisar.xposed.godmode.injection.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.injection.ViewHelper.TAG_GM_CMP;
import static com.kaisar.xposed.godmode.injection.util.CommonUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
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

/**
 * Hook {@link View#dispatchTouchEvent}，在编辑模式下拦截触摸事件。
 * <p>
 * 支持两种不同的交互模式：
 * <ul>
 *   <li><b>移除模式</b> – 点击选中，长按拖拽幻影视图至回收区，
 *       松手后应用移除规则并播放粒子动画。</li>
 *   <li><b>修改模式</b> – 点击选中，长按拖拽当前选中视图并吸附对齐，
 *       位置偏移量保存为修改规则。</li>
 * </ul>
 */
public final class EventHandlerHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

    // =========================================================================
    // 常量
    // =========================================================================

    private static final int MARK_COLOR = Color.argb(150, 139, 195, 75);
    private static final int GRID_SIZE_DP = 16;
    private static final int EDGE_SNAP_THRESHOLD_DP = 12;

    // =========================================================================
    // 编辑器状态
    // =========================================================================

    private boolean mIsInEditMode;
    private volatile boolean mMultiPointLock;
    public static volatile boolean mDragging;

    // =========================================================================
    // 通用触摸状态
    // =========================================================================

    private boolean mLongClick;
    private CheckForLongPress mPendingCheckForLongPress;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mHasBlockEvent;

    // =========================================================================
    // 移除模式状态
    // =========================================================================

    private Bitmap mSnapshot;
    private ViewRule mViewRule;
    private MaskView mMaskView;
    private CancelView mCancelView;
    private float mDeltaX, mDeltaY;

    // =========================================================================
    // 修改模式拖拽状态
    // =========================================================================

    private View mDragTarget;
    private float mDragStartRawX, mDragStartRawY;
    private int mDragStartMarginX, mDragStartMarginY;
    private int mGridSizePx, mSnapThresholdPx;

    // =========================================================================
    // Hook 入口
    // =========================================================================

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (!mIsInEditMode) return;
        View view = (View) param.thisObject;
        MotionEvent event = (MotionEvent) param.args[0];
        if (TAG_GM_CMP.equals(view.getTag())) return;
        if (!isEditableWindow(view)) {
            return;
        }
        param.setResult(dispatchTouchEvent(view, event));
    }

    private boolean isEditableWindow(View v) {
        WindowManager.LayoutParams wl = getWindowLayoutParams(v);
        if (wl == null) return false;
        int type = wl.type;
        if (type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) return true;
        return type > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW;
    }

    private WindowManager.LayoutParams getWindowLayoutParams(View v) {
        Object viewRootImpl = ViewHelper.findViewRootImplByChildView(v.getParent());
        if (viewRootImpl == null) return null;
        try {
            return (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(viewRootImpl, "mWindowAttributes");
        } catch (Exception e) {
            return null;
        }
    }

    private boolean dispatchTouchEvent(View v, MotionEvent event) {
        int mode = DispatchKeyEventHook.getInteractionMode();
        int action = event.getActionMasked();

        if (mode == DispatchKeyEventHook.MODE_INITIAL) {
            return true;
        }
        if (mode == DispatchKeyEventHook.MODE_MODIFY) {
            return handleModifyTouch(v, event);
        }
        return handleRemoveTouch(v, event, action);
    }

    // =========================================================================
    // 移除模式：点击选中，长按拖拽幻影 → 松手后应用规则
    // =========================================================================

    private boolean handleRemoveTouch(View v, MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (!beginTouch(v, false)) return false;
            Rect bounds = ViewHelper.getLocationInWindow(v);
            mDeltaX = event.getRawX() - bounds.left;
            mDeltaY = event.getRawY() - bounds.top;

        } else if (action == MotionEvent.ACTION_MOVE) {
            if (mLongClick) {
                mMaskView.updateOverlayBounds(
                        (int) (event.getRawX() - mDeltaX), (int) (event.getRawY() - mDeltaY),
                        v.getWidth(), v.getHeight());
                mMaskView.setMarked(
                        mCancelView.getRealBounds().intersect(mMaskView.getRealBounds()));
            }

        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mLongClick) {
                finishRemoveDrag(v);
            } else if (action == MotionEvent.ACTION_UP && DispatchKeyEventHook.isKeySelecting()) {
                DispatchKeyEventHook.selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
    }

    private void finishRemoveDrag(final View v) {
        Activity activity = ViewHelper.getAttachedActivityFromView(v);
        try {
            Preconditions.checkNotNull(activity);
        } catch (NullPointerException e) {
            return;
        }
        if (mCancelView != null) mCancelView.detachFromContainer();

        if (mMaskView != null && mMaskView.isMarked()) {
            // 已取消：还原视图，清理状态
            mMaskView.detachFromContainer();
            mViewRule.visibility = View.VISIBLE;
            ViewController.revokeRule(v, mViewRule);
            recycleNullableBitmap(mSnapshot);
            clearRemoveState();
        } else {
            // 已确认：播放粒子爆炸动画，然后持久化规则
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
            final ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(1000);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    mViewRule.visibility = View.GONE;
                    ViewController.applyRule(v, mViewRule);
                    ViewHelper.drawRuleMask(mSnapshot, mViewRule);
                    mMaskView.detachFromContainer();
                    new Thread(() -> {
                        try { GodModeManager.getDefault().writeRule(v.getContext().getPackageName(), mViewRule, mSnapshot); }
                        catch (Exception e) { Logger.e(TAG, "write rule fail", e); }
                        recycleNullableBitmap(mSnapshot);
                    }, "gm-write").start();
                }
                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    particleView.detachFromContainer();
                    clearRemoveState();
                }
            });
            particleView.boom(mMaskView);
        }
    }

    /** 初始化移除拖拽：克隆视图为遮罩，显示取消区域 */
    private void startRemoveDrag(View v) {
        try {
            Activity activity = Preconditions.checkNotNull(ViewHelper.getAttachedActivityFromView(v));
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
            mSnapshot = ViewHelper.snapshotView(ViewHelper.findTopParentViewByChildView(v));
            mViewRule = ViewHelper.makeRemoveRule(v);

            mCancelView = new CancelView(activity);
            mCancelView.attachToContainer(container);

            mMaskView = MaskView.makeMaskView(activity);
            mMaskView.setMaskOverlay(v);
            mMaskView.setMarkColor(MARK_COLOR);
            mMaskView.updateOverlayBounds(ViewHelper.getLocationInWindow(v));
            mMaskView.attachToContainer(container);

            ViewController.applyRule(v, mViewRule);
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            Logger.e(TAG, "startRemoveDrag fail", e);
        }
    }

    private void clearRemoveState() {
        mSnapshot = null;
        mMaskView = null;
        mCancelView = null;
        mViewRule = null;
    }

    // =========================================================================
    // 修改模式：点击选中，长按拖拽当前选中视图并吸附对齐
    // =========================================================================

    private boolean handleModifyTouch(View v, MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            if (!beginTouch(v, true)) return false;
            mDragStartRawX = event.getRawX();
            mDragStartRawY = event.getRawY();

        } else if (action == MotionEvent.ACTION_MOVE) {
            if (mLongClick && mDragTarget != null) {
                moveTargetTo(event.getRawX(), event.getRawY());
            }

        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mLongClick && mDragTarget != null) {
                finalizeModifyDrag(v.getContext().getPackageName());
            } else if (action == MotionEvent.ACTION_UP && !mLongClick) {
                DispatchKeyEventHook.selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
    }

    /** 开始拖拽当前选中的视图（非被触摸的视图） */
    private void startModifyDrag() {
        View target = DispatchKeyEventHook.getSelectedView();
        if (target == null) return;

        mDragTarget = target;
        float density = target.getResources().getDisplayMetrics().density;
        mGridSizePx = (int) (GRID_SIZE_DP * density + 0.5f);
        mSnapThresholdPx = (int) (EDGE_SNAP_THRESHOLD_DP * density + 0.5f);

        ViewGroup.LayoutParams lp = target.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mDragStartMarginX = mlp.leftMargin;
            mDragStartMarginY = mlp.topMargin;
        }

        target.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Logger.d(TAG, "[ModifyDrag] start " + target
                + " startMargin=(" + mDragStartMarginX + "," + mDragStartMarginY + ")");
    }

    /** 移动拖拽目标，应用网格吸附和兄弟视图边缘吸附 */
    private void moveTargetTo(float rawX, float rawY) {
        int newMarginX = mDragStartMarginX + (int) (rawX - mDragStartRawX);
        int newMarginY = mDragStartMarginY + (int) (rawY - mDragStartRawY);

        // 网格吸附
        newMarginX = Math.round(newMarginX / (float) mGridSizePx) * mGridSizePx;
        newMarginY = Math.round(newMarginY / (float) mGridSizePx) * mGridSizePx;

        // 兄弟视图边缘吸附
        int[] snapped = snapToSiblings(mDragTarget, newMarginX, newMarginY);
        newMarginX = snapped[0];
        newMarginY = snapped[1];

        ViewGroup.LayoutParams lp = mDragTarget.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mlp.leftMargin = newMarginX;
            mlp.topMargin = newMarginY;
            mDragTarget.setLayoutParams(mlp);
        }
    }

    /** 将目标边距吸附到兄弟视图边缘（在阈值范围内）。返回 {x, y} */
    private int[] snapToSiblings(View target, int targetLeftMargin, int targetTopMargin) {
        ViewParent parent = target.getParent();
        if (!(parent instanceof ViewGroup)) return new int[]{targetLeftMargin, targetTopMargin};
        ViewGroup container = (ViewGroup) parent;

        int targetRight = targetLeftMargin + target.getWidth();
        int targetBottom = targetTopMargin + target.getHeight();

        int snappedX = targetLeftMargin;
        int snappedY = targetTopMargin;

        for (int i = 0; i < container.getChildCount(); i++) {
            View sibling = container.getChildAt(i);
            if (sibling == target || sibling.getVisibility() != View.VISIBLE) continue;

            ViewGroup.LayoutParams slp = sibling.getLayoutParams();
            int sibLeft = slp instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) slp).leftMargin : sibling.getLeft();
            int sibTop = slp instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) slp).topMargin : sibling.getTop();
            int sibRight = sibLeft + sibling.getWidth();
            int sibBottom = sibTop + sibling.getHeight();

            // 左/右吸附
            if (Math.abs(targetLeftMargin - sibLeft) < mSnapThresholdPx) snappedX = sibLeft;
            if (Math.abs(targetLeftMargin - sibRight) < mSnapThresholdPx) snappedX = sibRight;
            if (Math.abs(targetRight - sibLeft) < mSnapThresholdPx) snappedX = sibLeft - target.getWidth();
            if (Math.abs(targetRight - sibRight) < mSnapThresholdPx) snappedX = sibRight - target.getWidth();

            // 上/下吸附
            if (Math.abs(targetTopMargin - sibTop) < mSnapThresholdPx) snappedY = sibTop;
            if (Math.abs(targetTopMargin - sibBottom) < mSnapThresholdPx) snappedY = sibBottom;
            if (Math.abs(targetBottom - sibTop) < mSnapThresholdPx) snappedY = sibTop - target.getHeight();
            if (Math.abs(targetBottom - sibBottom) < mSnapThresholdPx) snappedY = sibBottom - target.getHeight();
        }
        return new int[]{snappedX, snappedY};
    }

    /** 将最终拖拽位置持久化为位置修改规则 */
    private void finalizeModifyDrag(String packageName) {
        ViewGroup.LayoutParams lp = mDragTarget.getLayoutParams();
        int finalMarginX = 0, finalMarginY = 0;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            finalMarginX = mlp.leftMargin;
            finalMarginY = mlp.topMargin;
        }
        int deltaX = finalMarginX - mDragStartMarginX;
        int deltaY = finalMarginY - mDragStartMarginY;
        Logger.d(TAG, "[ModifyDrag] final delta=(" + deltaX + "," + deltaY + ")");

        if (deltaX != 0 || deltaY != 0) {
            ViewRule rule = ViewHelper.makeModifyRule(mDragTarget);
            rule.origLeftMargin = mDragStartMarginX;
            rule.origTopMargin = mDragStartMarginY;
            rule.modXOffset = deltaX;
            rule.modYOffset = deltaY;
            ViewHelper.fillCoordinates(rule, mDragTarget);
            Bitmap snapshot = ViewHelper.snapshotView(ViewHelper.findTopParentViewByChildView(mDragTarget));
            ViewHelper.drawRuleMask(snapshot, rule);
            GodModeManager.getDefault().writeRule(packageName, rule, snapshot);
            recycleNullableBitmap(snapshot);
        }
    }

    // =========================================================================
    // 共享的触摸开始/结束和长按检测
    // =========================================================================

    private boolean beginTouch(View v, boolean isModifyMode) {
        if (mMultiPointLock) {
            if (!isModifyMode) {
                Toast.makeText(v.getContext(), "不支持多点操作", Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        if (getWindowLayoutParams(v) == null) {
            if (!isModifyMode && !mHasBlockEvent) {
                Toast.makeText(v.getContext(), "该控件属于悬浮窗暂不支持编辑", Toast.LENGTH_SHORT).show();
                mHasBlockEvent = true;
            }
            return false;
        }
        mDragging = true;
        mMultiPointLock = true;
        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        mPendingCheckForLongPress = new CheckForLongPress(v, isModifyMode);
        mHandler.postDelayed(mPendingCheckForLongPress, ViewConfiguration.getLongPressTimeout());
        return true;
    }

    private void endTouch(View v) {
        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        mHandler.removeCallbacks(mPendingCheckForLongPress);
        mLongClick = false;
        mDragTarget = null;
        mHasBlockEvent = false;
        mMultiPointLock = false;
        mDragging = false;
    }

    /** 长按 Runnable：触发一次，根据模式决定启动哪种拖拽 */
    private class CheckForLongPress implements Runnable {
        private final WeakReference<View> viewRef;
        private final boolean isModifyMode;

        CheckForLongPress(View view, boolean isModifyMode) {
            this.viewRef = new WeakReference<>(view);
            this.isModifyMode = isModifyMode;
        }

        @Override
        public void run() {
            View view = viewRef.get();
            if (view == null) return;
            if (isModifyMode) {
                startModifyDrag();
            } else {
                startRemoveDrag(view);
            }
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mLongClick = true;
        }
    }

    // =========================================================================
    // 编辑模式开关
    // =========================================================================

    @Override
    public void onPropertyChange(Boolean enable) {
        mIsInEditMode = enable;
        if (!enable) {
            mHandler.removeCallbacks(mPendingCheckForLongPress);
            mLongClick = false;
            mDragTarget = null;
            mMultiPointLock = false;
            mDragging = false;
        }
    }
}
