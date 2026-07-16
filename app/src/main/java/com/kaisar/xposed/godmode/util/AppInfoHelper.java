package com.kaisar.xposed.godmode.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.kaisar.xposed.godmode.R;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class AppInfoHelper {

    private AppInfoHelper() {}

    public static String generateBackupFilename(Context context, String packageName)
            throws PackageManager.NameNotFoundException {
        DateTimeFormatter sdf = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").withLocale(Locale.getDefault());
        PackageManager pm = context.getPackageManager();
        ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
        String label = appInfo.loadLabel(pm).toString();
        return String.format(Locale.getDefault(), "%s_%s.zip", label, sdf.format(LocalDateTime.now()));
    }

}
