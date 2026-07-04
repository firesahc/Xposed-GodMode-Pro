package com.kaisar.xposed.godmode.injection.editor;

import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowManager;

import com.kaisar.xposed.godmode.editor.IRuleEditor;
import com.kaisar.xposed.godmode.engine.EditorInteractionMode;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.editor.gesture.GestureDispatcher;
import com.kaisar.xposed.godmode.injection.editor.gesture.ModifyGestureHandler;
import com.kaisar.xposed.godmode.injection.editor.gesture.RemoveGestureHandler;
import com.kaisar.xposed.godmode.injection.util.ViewUtils;

import java.lang.reflect.Field;

/**
 * 触摸事件处理器 — 从 EditorOrchestrator 抽取出的触摸/手势逻辑。
 * 负责编辑模式下的触摸事件分发、长按检测、拖拽手势等。
 */
public final class TouchEventHandler {

    private static final String TAG = "TouchEventHandler";
    private static final int LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    // =========================================================================
    // 回调接口 — 与 EditorOrchestrator 通信
    // =========================================================================

    public interface TouchCallback {
        boolean isKeySelecting();
        View getSelectedView();
        int getInteractionMode();
        void selectViewByTap(View v);
    }

    private final TouchCallback mCallback;
    private final IRuleEditor mRuleEditor;

    // =========================================================================
    // 触摸状态字段
    // =========================================================================

    private boolean mMultiPointLock;
    private boolean mDragging;
    private boolean mLongClick;
    private boolean mHasBlockEvent;
    private Handler mHandler;

    private RemoveGestureHandler.RemoveState mRemoveState;
    private ModifyGestureHandler.ModifyState mModifyState;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private float mDeltaX, mDeltaY;
    private float mDragStartRawX, mDragStartRawY;

    // =========================================================================
    // 窗口属性反射字段
    // =========================================================================

    private static Field sWindowAttributesField;

    // =========================================================================
    // 构造器
    // =========================================================================

    public TouchEventHandler(TouchCallback callback, IRuleEditor ruleEditor) {
        this.mCallback = callback;
        this.mRuleEditor = ruleEditor;
    }

    // =========================================================================
    // Handler
    // =========================================================================

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new Handler(Looper.getMainLooper());
        }
        return mHandler;
    }

    // =========================================================================
    // 状态查询
    // =========================================================================

    public boolean isDragging() {
        return mDragging;
    }

    void resetState() {
        getHandler().removeCallbacksAndMessages(null);
        mLongClick = false;
        mMultiPointLock = false;
        mDragging = false;
        RemoveGestureHandler.clearState(mRemoveState);
        mRemoveState = null;
        mModifyState = null;
    }

    // =========================================================================
    // 触摸事件入口 — 由 EditorOrchestrator 调用
    // =========================================================================

    /**
     * 编辑模式触摸事件入口（预先经过 mIsInEditMode 和 TAG_GM_CMP 过滤）。
     */
    public boolean onTouchEvent(View view, MotionEvent event) {
        if (!isEditableWindow(view)) return false;
        return dispatchTouchEvent(view, event);
    }

    // =========================================================================
    // 窗口类型判断
    // =========================================================================

    private boolean isEditableWindow(View v) {
        WindowManager.LayoutParams wl = getWindowLayoutParams(v);
        if (wl == null) return false;
        int type = wl.type;
        if (type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) return true;
        return type > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW;
    }

    private WindowManager.LayoutParams getWindowLayoutParams(View v) {
        Object viewRootImpl = ViewUtils.findViewRootImplByChildView(v.getParent());
        if (viewRootImpl == null) return null;
        try {
            if (sWindowAttributesField == null) {
                sWindowAttributesField = viewRootImpl.getClass().getDeclaredField("mWindowAttributes");
                sWindowAttributesField.setAccessible(true);
            }
            return (WindowManager.LayoutParams) sWindowAttributesField.get(viewRootImpl);
        } catch (Exception e) {
            Logger.e(TAG, "getWindowLayoutParams reflection failed", e);
            return null;
        }
    }

    // =========================================================================
    // 触摸事件分发
    // =========================================================================

    private boolean dispatchTouchEvent(View v, MotionEvent event) {
        int mode = mCallback.getInteractionMode();
        int action = event.getActionMasked();

        if (mode == EditorInteractionMode.INITIAL) {
            return true;
        }
        if (mode == EditorInteractionMode.MODIFY) {
            return handleModifyTouch(v, event);
        }
        return handleRemoveTouch(v, event, action);
    }

    // =========================================================================
    // 移除模式触摸处理 — 长按拖拽视图到取消区域执行移除
    // =========================================================================

    private boolean handleRemoveTouch(View v, MotionEvent event, int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            if (!beginTouch(v, false)) return false;
            android.graphics.Rect bounds = ViewUtils.getLocationInWindow(v);
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
                RemoveGestureHandler.finishDrag(v, mRemoveState, mRuleEditor);
                RemoveGestureHandler.clearState(mRemoveState);
                mRemoveState = null;
            } else if (action == MotionEvent.ACTION_UP && mCallback.isKeySelecting()) {
                mCallback.selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
    }

    // =========================================================================
    // 修改模式触摸处理 — 长按拖拽修改视图属性
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
                ModifyGestureHandler.finalizeDrag(mModifyState,
                        v.getContext().getPackageName(), mRuleEditor);
                mModifyState = null;
            } else if (action == MotionEvent.ACTION_UP && !mLongClick) {
                mCallback.selectViewByTap(v);
            }
            endTouch(v);
        }
        return true;
    }

    // =========================================================================
    // 触摸开始/结束 — 手势调度与长按检测
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
        getHandler().postDelayed(() -> onLongPress(v, isModifyMode), LONG_PRESS_TIMEOUT);
        return true;
    }

    private void endTouch(View v) {
        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        getHandler().removeCallbacksAndMessages(null);
        mLongClick = false;
        mHasBlockEvent = false;
        mMultiPointLock = false;
        mDragging = false;
    }

    /** Handle long press gesture on view — starts drag for modify or remove mode. */
    private void onLongPress(View v, boolean isModifyMode) {
        if (isModifyMode) {
            View target = mCallback.getSelectedView();
            if (target != null) {
                mModifyState = ModifyGestureHandler.startDrag(target);
            }
        } else {
            mRemoveState = RemoveGestureHandler.startDrag(v);
        }
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        mLongClick = true;
    }
}
