package com.kaisar.xposed.godmode.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.injection.ViewHelper.TAG_GM_CMP;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowManager;

import com.kaisar.xposed.godmode.injection.ViewHelper;
import com.kaisar.xposed.godmode.injection.editor.gesture.GestureDispatcher;
import com.kaisar.xposed.godmode.injection.editor.gesture.ModifyGestureHandler;
import com.kaisar.xposed.godmode.injection.editor.gesture.RemoveGestureHandler;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.Property;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook {@link View#dispatchTouchEvent}，在编辑模式下拦截触摸事件。
 * <p>
 * 薄壳调度器：不持有移除/修改模式的业务状态，通过聚合状态对象
 * 委托给 {@link RemoveGestureHandler} / {@link ModifyGestureHandler}。
 * <p>
 * 支持两种不同的交互模式：
 * <ul>
 *   <li><b>移除模式</b> – 点击选中，长按拖拽幻影视图至回收区，
 *       松手后应用移除规则并播放粒子动画。</li>
 *   <li><b>修改模式</b> – 点击选中，长按拖拽当前选中视图并吸附对齐，
 *       位置偏移量保存为修改规则。</li>
 * </ul>
 */
public final class TouchInterceptor extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

    // =========================================================================
    // 常量
    // =========================================================================

    private static final int LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

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
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mHasBlockEvent;

    // =========================================================================
    // 聚合状态（替代原有 8 个散落字段）
    // =========================================================================

    private RemoveGestureHandler.RemoveState mRemoveState;
    private ModifyGestureHandler.ModifyState mModifyState;

    // =========================================================================
    // 触摸坐标偏移（保留在 TouchInterceptor，handleRemove/Move 需要）
    // =========================================================================

    private float mDeltaX, mDeltaY;
    private float mDragStartRawX, mDragStartRawY;

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
        int mode = KeyInterceptor.getInteractionMode();
        int action = event.getActionMasked();

        if (mode == KeyInterceptor.MODE_INITIAL) {
            return true;
        }
        if (mode == KeyInterceptor.MODE_MODIFY) {
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
            if (mLongClick && mRemoveState != null && mRemoveState.maskView != null) {
                mRemoveState.maskView.updateOverlayBounds(
                        (int) (event.getRawX() - mDeltaX), (int) (event.getRawY() - mDeltaY),
                        v.getWidth(), v.getHeight());
                mRemoveState.maskView.setMarked(
                        mRemoveState.cancelView.getRealBounds().intersect(
                                mRemoveState.maskView.getRealBounds()));
            }

        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mLongClick && mRemoveState != null) {
                RemoveGestureHandler.finishDrag(v, mRemoveState);
                RemoveGestureHandler.clearState(mRemoveState);
                mRemoveState = null;
            } else if (action == MotionEvent.ACTION_UP && KeyInterceptor.isKeySelecting()) {
                KeyInterceptor.selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
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
            if (mLongClick && mModifyState != null) {
                float dx = event.getRawX() - mDragStartRawX;
                float dy = event.getRawY() - mDragStartRawY;
                ModifyGestureHandler.moveTarget(mModifyState, dx, dy);
            }

        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (mLongClick && mModifyState != null) {
                ModifyGestureHandler.finalizeDrag(mModifyState, v.getContext().getPackageName());
                mModifyState = null;
            } else if (action == MotionEvent.ACTION_UP && !mLongClick) {
                KeyInterceptor.selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
    }

    // =========================================================================
    // 共享的触摸开始/结束和长按检测
    // =========================================================================

    private boolean beginTouch(View v, boolean isModifyMode) {
        boolean[] draggingRef = new boolean[1];
        if (!GestureDispatcher.tryBeginTouch(v, isModifyMode,
                mMultiPointLock, new boolean[]{mHasBlockEvent},
                this::getWindowLayoutParams, draggingRef)) {
            return false;
        }
        mDragging = draggingRef[0];
        mMultiPointLock = true;
        mHandler.postDelayed(() -> onLongPress(v, isModifyMode), LONG_PRESS_TIMEOUT);
        return true;
    }

    /**
     * 结束触摸：完整清理所有触摸状态。
     * <p>
     * 比 {@link GestureDispatcher#tryBeginTouch} 的职责更广：
     * 除了释放触摸拦截，还会重置 mLongClick/mHasBlockEvent/mMultiPointLock/mDragging
     * 并取消所有排队的 Handler 消息（包括长按回调）。
     * GestureDispatcher 的旧 endTouch() 只清理单个长按回调，故已废弃删除。
     */
    private void endTouch(View v) {
        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        mHandler.removeCallbacksAndMessages(null);
        mLongClick = false;
        mHasBlockEvent = false;
        mMultiPointLock = false;
        mDragging = false;
    }

    /** 长按触发：根据模式启动移除或修改拖拽 */
    private void onLongPress(View v, boolean isModifyMode) {
        if (isModifyMode) {
            View target = KeyInterceptor.getSelectedView();
            if (target != null) {
                mModifyState = ModifyGestureHandler.startDrag(target);
            }
        } else {
            mRemoveState = RemoveGestureHandler.startDrag(v);
        }
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mLongClick = true;
    }

    // =========================================================================
    // 编辑模式开关
    // =========================================================================

    @Override
    public void onPropertyChange(Boolean enable) {
        if (enable == null) return;
        mIsInEditMode = enable;
        Logger.d(TAG, "[EventHandler] edit mode: " + enable);
        if (!enable) {
            mHandler.removeCallbacksAndMessages(null);
            mLongClick = false;
            mMultiPointLock = false;
            mDragging = false;
            RemoveGestureHandler.clearState(mRemoveState);
            mRemoveState = null;
            mModifyState = null;
        }
    }
}
