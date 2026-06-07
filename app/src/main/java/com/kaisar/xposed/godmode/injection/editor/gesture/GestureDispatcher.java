package com.kaisar.xposed.godmode.injection.editor.gesture;

import android.os.Handler;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 手势事件分发器 — 触摸开始/结束、长按检测等共享逻辑。
 * <p>
 * 为 TouchInterceptor 提供 beginTouch/endTouch 静态辅助方法，
 * 封装多点锁定、窗口类型检测、长按调度等跨模式共享逻辑。
 */
public final class GestureDispatcher {

    private static final long LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout();

    private GestureDispatcher() {}

    /**
     * 尝试开始触摸：检查多点锁定、悬浮窗限制，成功后调度长按检测。
     *
     * @param v            触摸目标视图
     * @param isModifyMode 是否为修改模式
     * @param multiPointLock 是否已有多点操作
     * @param hasBlockEvent 引用标记 — 悬浮窗 Toast 只显示一次
     * @param handler      用于调度长按的 Handler
     * @param getWindowParams 窗口布局参数获取器
     * @return 如果触摸被允许则返回非 null 的 Runnable（长按检测），否则返回 null
     */
    public static Runnable tryBeginTouch(View v, boolean isModifyMode,
            boolean multiPointLock, boolean[] hasBlockEvent,
            Handler handler, WindowParamsProvider getWindowParams) {
        if (multiPointLock) {
            if (!isModifyMode) {
                Toast.makeText(v.getContext(), "不支持多点操作", Toast.LENGTH_SHORT).show();
            }
            return null;
        }
        if (getWindowParams.getWindowLayoutParams(v) == null) {
            if (!isModifyMode && hasBlockEvent != null && !hasBlockEvent[0]) {
                Toast.makeText(v.getContext(), "该控件属于悬浮窗暂不支持编辑", Toast.LENGTH_SHORT).show();
                hasBlockEvent[0] = true;
            }
            return null;
        }

        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);

        Runnable longPress = () -> { /* 由 TouchInterceptor 设置具体行为 */ };
        handler.postDelayed(longPress, LONG_PRESS_TIMEOUT);
        return longPress;
    }

    /**
     * 结束触摸：清理长按回调、释放触摸拦截。
     */
    public static void endTouch(View v, Handler handler, Runnable pendingLongPress) {
        ViewParent parent = v.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        if (pendingLongPress != null) {
            handler.removeCallbacks(pendingLongPress);
        }
    }

    /** 窗口布局参数提供者（适配 TouchInterceptor 的私有方法） */
    public interface WindowParamsProvider {
        WindowManager.LayoutParams getWindowLayoutParams(View v);
    }
}
