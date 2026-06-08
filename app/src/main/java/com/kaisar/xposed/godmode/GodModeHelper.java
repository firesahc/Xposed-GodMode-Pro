package com.kaisar.xposed.godmode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.service.NotificationService;

public final class GodModeHelper {

    private static final String TAG = "GodMode";

    public static void startNotificationService(Context context) {
        try {
            Intent intent = new Intent(context, NotificationService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "[GodModeHelper] startNotificationService failed", e);
        }
    }

    public static void setEditModeEnabled(Context context, boolean enabled) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putBoolean("editor_switch", enabled).apply();
        GodModeManager.getDefault().setEditMode(enabled);
    }

    public static boolean isEditModeEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("editor_switch", false);
    }

    public static boolean isMasterEnabled(Context context, int prefKeyMasterResId) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(prefKeyMasterResId), false);
    }
}
