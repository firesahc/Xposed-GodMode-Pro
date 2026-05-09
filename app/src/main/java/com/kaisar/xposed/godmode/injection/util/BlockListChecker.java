package com.kaisar.xposed.godmode.injection.util;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.text.TextUtils;

import com.kaisar.xposed.godmode.BuildConfig;

import java.util.List;

public final class BlockListChecker {

    private BlockListChecker() {}

    public static boolean isBlocked(String packageName) {
        if (TextUtils.equals("com.android.systemui", packageName)) {
            return true;
        }
        if (TextUtils.equals(BuildConfig.APPLICATION_ID, packageName)) {
            return true;
        }
        try {
            if (isLauncher(packageName)) return true;
            if (isInputMethod(packageName)) return true;
            if (hasNoActivities(packageName)) return true;
        } catch (Throwable t) {
            Logger.e(TAG, "[GodMode] checkBlockList failed, allowing all", t);
        }
        return false;
    }

    private static boolean isLauncher(String packageName) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveInfos;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            resolveInfos = PackageManagerUtils.queryIntentActivities(homeIntent, null, PackageManager.MATCH_ALL, 0);
        } else {
            resolveInfos = PackageManagerUtils.queryIntentActivities(homeIntent, null, 0, 0);
        }
        if (resolveInfos != null) {
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (!TextUtils.equals("com.android.settings", packageName)
                        && TextUtils.equals(resolveInfo.activityInfo.packageName, packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInputMethod(String packageName) {
        Intent keyboardIntent = new Intent("android.view.InputMethod");
        List<ResolveInfo> resolveInfos;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            resolveInfos = PackageManagerUtils.queryIntentServices(keyboardIntent, null, PackageManager.MATCH_ALL, 0);
        } else {
            resolveInfos = PackageManagerUtils.queryIntentServices(keyboardIntent, null, 0, 0);
        }
        if (resolveInfos != null) {
            for (ResolveInfo resolveInfo : resolveInfos) {
                if (TextUtils.equals(resolveInfo.serviceInfo.packageName, packageName)) {
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
