package com.kaisar.xposed.godmode.engine.applier;

import android.view.View;

import com.kaisar.xposed.godmode.engine.util.Logger;

/**
 * View.setVisibility 瀹夊叏灏佽 鈥?鍙嶅皠澶辫触鏃跺洖閫€鍒?alpha 鎺у埗銆?
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
                Logger.w("GodMode", "[ViewCompat] setVisibility fallback also failed", inner);
            }
        }
    }
}
