package com.kaisar.xposed.godmode.platform.xposed;

import android.content.Context;
import android.content.ContextWrapper;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.ViewUtils;

import de.robv.android.xposed.XposedHelpers;

/**
 * Xposed fallback for ContextWrapper instances whose public base-context accessor returns itself.
 * Installed only by the Xposed composition root.
 */
public final class XposedContextBaseAccessor implements ViewUtils.ContextBaseAccessor {

    private static final String TAG = "XposedContextBaseAccessor";

    @Override
    public Context getBaseContext(ContextWrapper context) {
        Context baseContext = context.getBaseContext();
        if (baseContext != context) return baseContext;
        try {
            return (Context) XposedHelpers.getObjectField(context, "mBase");
        } catch (Exception failure) {
            Logger.w(TAG, "getBaseContext reflection failed for context", failure);
            return null;
        }
    }
}
