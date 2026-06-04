package com.kaisar.xposed.godmode.engine.applier;

import android.util.Log;
import android.view.View;

/**
 * View.setVisibility 安全封装 — 反射失败时回退到 alpha 控制。
 */
public final class ViewCompat {

    private ViewCompat() {}

    public static void setVisibility(View view, int visibility) {
        try {
            view.setVisibility(visibility);
        } catch (Exception e) {
            try {
                view.setAlpha(visibility == View.VISIBLE ? 1f : 0f);
            } catch (Exception inner) {
                Log.w("GodMode", "[ViewCompat] setVisibility fallback also failed", inner);
            }
        }
    }
}
