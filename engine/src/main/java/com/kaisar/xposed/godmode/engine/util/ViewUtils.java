package com.kaisar.xposed.godmode.engine.util;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

/**
 * 通用视图工具方法 — 不依赖 app 模块类型，可安全放入 engine。
 */
public final class ViewUtils {

    private ViewUtils() {}

    /**
     * 获取视图在窗口中的位置和尺寸。
     */
    public static Rect getLocationInWindow(View view) {
        int[] out = new int[2];
        view.getLocationInWindow(out);
        int l = out[0];
        int t = out[1];
        return new Rect(l, t, l + view.getWidth(), t + view.getHeight());
    }

    /**
     * 判断视图是否已挂载到窗口。
     */
    public static boolean isAttachedToWindow(View view) {
        return view != null && view.isAttachedToWindow();
    }

    /**
     * 判断视图是否可见且已挂载。
     */
    public static boolean isVisibleAndAttached(View view) {
        return view != null && view.getVisibility() == View.VISIBLE
                && view.isAttachedToWindow();
    }

    /**
     * 获取视图的边界矩形（相对于窗口）。
     */
    public static Rect getBounds(View view) {
        int[] out = new int[2];
        view.getLocationInWindow(out);
        return new Rect(out[0], out[1], out[0] + view.getWidth(),
                out[1] + view.getHeight());
    }

    /**
     * 将 dp 值转换为当前密度的 px 值。
     */
    public static int dpToPx(Context context, int dp) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dp * scale + 0.5f);
    }

    /**
     * 将 px 值转换为当前密度的 dp 值。
     */
    public static int pxToDp(Context context, int px) {
        float scale = context.getResources().getDisplayMetrics().density;
        return (int) (px / scale + 0.5f);
    }

    /**
     * 安全回收 Bitmap（委托 CommonUtils）。
     */
    public static void recycleBitmap(android.graphics.Bitmap bitmap) {
        CommonUtils.recycleNullableBitmap(bitmap);
    }
}
