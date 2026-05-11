package com.kaisar.xposed.godmode.injection.util;

import android.content.res.Resources;
import android.content.res.XmlResourceParser;

public final class GmResources {

    private static Resources sModuleRes;

    private GmResources() {}

    public static void init(Resources moduleRes) {
        sModuleRes = moduleRes;
    }

    public static XmlResourceParser getLayout(int id) {
        return sModuleRes.getLayout(id);
    }

    public static CharSequence getText(int id) throws Resources.NotFoundException {
        return sModuleRes.getText(id);
    }

    public static String getString(int id) throws Resources.NotFoundException {
        return sModuleRes.getString(id);
    }

    public static String getString(int id, Object... formatArgs) throws Resources.NotFoundException {
        return sModuleRes.getString(id, formatArgs);
    }
}
