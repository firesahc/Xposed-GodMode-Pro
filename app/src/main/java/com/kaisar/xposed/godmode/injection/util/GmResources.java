package com.kaisar.xposed.godmode.injection.util;

import android.content.res.Resources;

import com.kaisar.xposed.godmode.injection.GodModeInjector;

public final class GmResources {

    private GmResources() {}

    private static Resources getGmResource() {
        return GodModeInjector.moduleRes;
    }

    public static CharSequence getText(int id) throws Resources.NotFoundException {
        return getGmResource().getText(id);
    }

    public static String getString(int id) throws Resources.NotFoundException {
        return getGmResource().getString(id);
    }

    public static String getString(int id, Object... formatArgs) throws Resources.NotFoundException {
        return getGmResource().getString(id, formatArgs);
    }
}
