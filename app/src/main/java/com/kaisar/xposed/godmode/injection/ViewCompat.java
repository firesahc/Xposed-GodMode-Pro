package com.kaisar.xposed.godmode.injection;

import android.view.View;

public final class ViewCompat {

    public static void setVisibility(View view, int visibility) {
        try {
            view.setVisibility(visibility);
        } catch (Exception e) {
            try {
                view.setAlpha(visibility == View.VISIBLE ? 1f : 0f);
            } catch (Exception ignored) {
            }
        }
    }
}
