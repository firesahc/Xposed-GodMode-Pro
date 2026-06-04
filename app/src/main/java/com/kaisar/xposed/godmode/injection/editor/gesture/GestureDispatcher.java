package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.kaisar.xposed.godmode.injection.editor.EditorState;

/**
 * 手势事件分发器 — 长按检测 + 模式分派状态机。
 * 计划从 EventHandlerHook 提取的核心触摸事件处理逻辑。
 * <p>
 * 当前为骨架实现，待第4阶段后续将 Hook 中的实际触摸处理迁移至此。
 */
public class GestureDispatcher {

    private static final long LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private EditorState mCurrentState = EditorState.MODE_INITIAL;
    private View mTargetView;
    private float mDownX, mDownY;
    private boolean mIsDragging;
    private Runnable mLongPressRunnable;

    public void setState(EditorState state) {
        mCurrentState = state;
    }

    public EditorState getState() {
        return mCurrentState;
    }

    /**
     * 处理触摸事件，返回 true 表示事件已被消费。
     * @param v     触摸目标视图
     * @param event 原始 MotionEvent
     * @param state 当前编辑器状态
     * @return 是否消费事件
     */
    public boolean onTouch(View v, MotionEvent event, EditorState state) {
        // TODO: 从 EventHandlerHook.beforeHookedMethod 迁移实际触摸处理逻辑
        // 当前仅保留骨架结构，Hook 中的逻辑继续正常运行
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTargetView = v;
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mIsDragging = false;
                scheduleLongPress(v, state);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getRawX() - mDownX) > 10
                        || Math.abs(event.getRawY() - mDownY) > 10) {
                    mIsDragging = true;
                    cancelLongPress();
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress();
                mTargetView = null;
                mIsDragging = false;
                return false;
        }
        return false;
    }

    private void scheduleLongPress(View v, EditorState state) {
        cancelLongPress();
        mLongPressRunnable = () -> {
            if (state == EditorState.MODE_REMOVE) {
                // TODO: 触发 RemoveGestureHandler
            } else if (state == EditorState.MODE_MODIFY) {
                // TODO: 触发 ModifyGestureHandler
            }
        };
        mHandler.postDelayed(mLongPressRunnable, LONG_PRESS_TIMEOUT);
    }

    private void cancelLongPress() {
        if (mLongPressRunnable != null) {
            mHandler.removeCallbacks(mLongPressRunnable);
            mLongPressRunnable = null;
        }
    }

    public boolean isDragging() {
        return mIsDragging;
    }

    public View getTargetView() {
        return mTargetView;
    }
}
