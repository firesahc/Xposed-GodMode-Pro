package com.kaisar.xposed.godmode;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.service.NotificationService;

public final class EditModeController {

    private static final String TAG = "EditModeController";

    public static void startNotificationService(Context context) {
        try {
            Intent intent = new Intent(context, NotificationService.class);
            context.startForegroundService(intent);
        } catch (Exception e) {
            Logger.w(TAG, "startNotificationService failed", e);
        }
    }

    public static void setEditModeEnabled(Context context, boolean enabled) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putBoolean("editor_switch", enabled).apply();
        RuleServiceClient.getDefault().setEditMode(enabled);
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
