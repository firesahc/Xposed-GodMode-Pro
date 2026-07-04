package com.kaisar.xposed.godmode.editor.gesture;

import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Toast;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.util.GmResources;

/**
 * 手势事件分发器 — 触摸开始条件校验。
 * <p>
 * 为 TouchInterceptor 提供 {@link #tryBeginTouch} 静态辅助方法，
 * 封装多点锁定检测、悬浮窗限制检查等跨模式共享逻辑。
 * 触摸状态管理和长按调度由调用方自行处理。
 */
public final class GestureDispatcher {

    private GestureDispatcher() {}

    /**
     * 尝试开始触摸：检查多点锁定、悬浮窗限制，成功后设置 dragging 标记。
     * <p>
     * 长按检测由调用方（TouchInterceptor）自行调度，本方法仅做条件校验。
     *
     * @param v               触摸目标视图
     * @param isModifyMode    是否为修改模式
     * @param multiPointLock  是否已有多点操作
     * @param hasBlockEvent   引用标记 — 悬浮窗 Toast 只显示一次
     * @param getWindowParams 窗口布局参数获取器
     * @param draggingRef     引用标记 — 触摸成功时设置为 true（对应调用方的 mDragging）
     * @return 如果触摸被允许则返回 true，否则返回 false
     */
    public static boolean tryBeginTouch(View v, boolean isModifyMode,
            boolean multiPointLock, boolean[] hasBlockEvent,
            WindowParamsProvider getWindowParams, boolean[] draggingRef) {
        if (multiPointLock) {
            if (!isModifyMode) {
                Toast.makeText(v.getContext(), GmResources.getString(R.string.toast_multi_touch_not_supported), Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        if (getWindowParams.getWindowLayoutParams(v) == null) {
            if (!isModifyMode && hasBlockEvent != null && !hasBlockEvent[0]) {
                Toast.makeText(v.getContext(), GmResources.getString(R.string.toast_float_window_not_editable), Toast.LENGTH_SHORT).show();
                hasBlockEvent[0] = true;
            }
            return false;
        }

        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        draggingRef[0] = true;
        return true;
    }

    /** 窗口布局参数提供者（适配 TouchInterceptor 的私有方法） */
    public interface WindowParamsProvider {
        WindowManager.LayoutParams getWindowLayoutParams(View v);
    }
}
