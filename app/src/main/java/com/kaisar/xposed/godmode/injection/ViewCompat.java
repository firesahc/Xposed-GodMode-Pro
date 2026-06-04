package com.kaisar.xposed.godmode.injection;

import android.view.View;

/**
 * View.setVisibility 安全封装 — 委托 engine/applier/ViewCompat。
 * 保留此类以维持现有引用兼容。
 */
public final class ViewCompat {

    public static void setVisibility(View view, int visibility) {
        com.kaisar.xposed.godmode.engine.applier.ViewCompat.setVisibility(view, visibility);
    }
}
