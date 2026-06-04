package com.kaisar.xposed.godmode.engine.util;

import android.graphics.Bitmap;

/**
 * 通用工具方法 — 统一放在 engine 模块供所有层使用。
 */
public final class CommonUtils {

    /**
     * 安全回收 Bitmap，自动处理 null 和已回收状态。
     */
    public static void recycleNullableBitmap(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }
}
