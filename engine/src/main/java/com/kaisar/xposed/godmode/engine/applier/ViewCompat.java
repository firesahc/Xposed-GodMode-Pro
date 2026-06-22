package com.kaisar.xposed.godmode.engine.applier;

import android.view.View;

import com.kaisar.xposed.godmode.engine.util.Logger;

/**
 * View.setVisibility 安全封装 — 反射失败时回退到 alpha 控制。
 */
public final class ViewCompat {

    private static final String TAG = "ViewCompat";

    private ViewCompat() {}

    public static void setVisibility(View view, int visibility) {
        try {
            view.setVisibility(visibility);
        } catch (Exception e) {
            Logger.w(TAG, "setVisibility failed, using alpha fallback", e);
            try {
                view.setAlpha(visibility == View.VISIBLE ? 1f : 0f);
            } catch (Exception inner) {
                Logger.w(TAG, "setVisibility fallback also failed", inner);
            }
        }
    }
}
