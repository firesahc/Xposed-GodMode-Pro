package com.kaisar.xposed.godmode.injection.util;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.engine.util.Logger;

import java.util.List;

public final class BlockListChecker {

    private static final String TAG = "BlockListChecker";

    private BlockListChecker() {}

    public static boolean isBlocked(String packageName) {
        if (TextUtils.equals("com.android.systemui", packageName)) {
            Logger.d(TAG, "[BlockListChecker] blocked (systemui): " + packageName);
            return true;
        }
        if (TextUtils.equals(BuildConfig.APPLICATION_ID, packageName)) {
            Logger.d(TAG, "[BlockListChecker] blocked (self): " + packageName);
            return true;
        }
        try {
            if (isLauncher(packageName)) {
                Logger.d(TAG, "[BlockListChecker] blocked (launcher): " + packageName);
                return true;
            }
            if (isInputMethod(packageName)) {
                Logger.d(TAG, "[BlockListChecker] blocked (IME): " + packageName);
                return true;
            }
            if (hasNoActivities(packageName)) {
                Logger.d(TAG, "[BlockListChecker] blocked (no activities): " + packageName);
                return true;
            }
        } catch (Throwable t) {
            Logger.e(TAG, "[BlockListChecker] checkBlockList failed, allowing all", t);
        }
        return false;
    }

    private static boolean isLauncher(String packageName) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveInfos = PackageManagerUtils.queryIntentActivities(homeIntent, null, PackageManager.MATCH_ALL, 0);
        if (resolveInfos != null) {
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (resolveInfo.activityInfo != null
                        && !TextUtils.equals("com.android.settings", packageName)
                        && TextUtils.equals(resolveInfo.activityInfo.packageName, packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInputMethod(String packageName) {
        Intent keyboardIntent = new Intent("android.view.InputMethod");
        List<ResolveInfo> resolveInfos = PackageManagerUtils.queryIntentServices(keyboardIntent, null, PackageManager.MATCH_ALL, 0);
        if (resolveInfos != null) {
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (resolveInfo.serviceInfo != null
                        && TextUtils.equals(resolveInfo.serviceInfo.packageName, packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasNoActivities(String packageName) {
        PackageInfo packageInfo = PackageManagerUtils.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES, 0);
        return packageInfo != null && packageInfo.activities != null && packageInfo.activities.length == 0;
    }
}
