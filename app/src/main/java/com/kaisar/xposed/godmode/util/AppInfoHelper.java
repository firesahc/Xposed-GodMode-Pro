package com.kaisar.xposed.godmode.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.kaisar.xposed.godmode.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class AppInfoHelper {

    private AppInfoHelper() {}

    public static String generateBackupFilename(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault());
        PackageManager pm = context.getPackageManager();
        ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
        String label = appInfo.loadLabel(pm).toString();
        return String.format(Locale.getDefault(), "%s_%s.gz", label, sdf.format(new Date()));
    }

    public static CharSequence resolveAppLabel(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return appInfo.loadLabel(pm);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    public static String getRuleTypeName(Context context, boolean isModify) {
        return context.getString(isModify ? R.string.rule_type_modify : R.string.rule_type_remove);
    }
}
