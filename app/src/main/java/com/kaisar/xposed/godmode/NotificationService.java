package com.kaisar.xposed.godmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;

public final class NotificationService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "NotificationService";

    @Override
    public void onCreate() {
        super.onCreate();
        createControlChannel();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isMasterEnabled()) {
            handleEditToggle(intent);
        } else {
            showNotification(false);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }, 300);
        }
        return START_STICKY;
    }

    private void handleEditToggle(Intent intent) {
        boolean editMode = GodModeHelper.isEditModeEnabled(this);
        if (intent != null && TextUtils.equals(intent.getAction(), Intent.ACTION_EDIT)) {
            if (!GodModeManager.getDefault().hasLight()) {
                Toast.makeText(this, R.string.not_active_module, Toast.LENGTH_SHORT).show();
                return;
            }
            editMode = !editMode;
            GodModeHelper.setEditModeEnabled(this, editMode);
        }
        showNotification(editMode);
    }

    private void createControlChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(TAG, "Control panel", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Let there be light");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void showNotification(boolean editMode) {
        Notification notification = buildNotification(editMode);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    private Notification buildNotification(boolean editMode) {
        Intent managerIntent = new Intent(this, SettingsActivity.class);
        PendingIntent managerPendingIntent = PendingIntent.getActivity(this, 0, managerIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        Intent intent = new Intent(this, NotificationService.class);
        intent.setAction(Intent.ACTION_EDIT);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, TAG)
                .setSmallIcon(R.drawable.ic_angel_small)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_angel_normal))
                .setContentTitle(getText(R.string.app_name))
                .setContentText(editMode ? getString(R.string.enter_edit) : getString(R.string.exit_edit))
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_manage, getString(R.string.manage), managerPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(false)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    private void setEditModeEnable(boolean enable) {
        GodModeHelper.setEditModeEnabled(this, enable);
    }

    public boolean isEditMode() {
        return GodModeHelper.isEditModeEnabled(this);
    }

    public boolean isMasterEnabled() {
        return GodModeHelper.isMasterEnabled(this, R.string.pref_key_master);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (TextUtils.equals(key, getString(R.string.pref_key_master))) {
            if (sharedPreferences.getBoolean(key, false)) {
                showNotification(isEditMode());
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        } else if (TextUtils.equals(key, getString(R.string.pref_key_editor))) {
            if (isMasterEnabled()) {
                showNotification(sharedPreferences.getBoolean(key, false));
            }
        }
    }
}
