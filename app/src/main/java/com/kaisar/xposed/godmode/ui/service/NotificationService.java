package com.kaisar.xposed.godmode.ui.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.core.PlatformCapabilities;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.ui.EditModeController;
import com.kaisar.xposed.godmode.ui.SettingsActivity;
import com.kaisar.xposed.godmode.util.TaskExecutor;

public final class NotificationService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "NotificationService";
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final RuleServiceClient.ObserverCallback mEditObserver =
            new RuleServiceClient.ObserverCallback() {
                @Override public void onEditModeChanged(boolean enabled, long editRevision,
                                                        long connectionEpoch) {
                    mMainHandler.post(() -> {
                        RuleServiceClient client = RuleServiceClient.getDefault();
                        if (isMasterEnabled()
                                && client.isCurrentEditEvent(connectionEpoch, editRevision)) {
                            showNotification(enabled);
                        }
                    });
                }

                @Override public void onRulesInvalidated(String packageName, long generation,
                                                         long connectionEpoch) { }
            };
    private final Runnable mBinderDeathListener = () ->
            mMainHandler.post(() -> {
                if (isMasterEnabled()) showNotification(false);
            });

    @Override
    public void onCreate() {
        super.onCreate();
        createControlChannel();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
        RuleServiceClient client = RuleServiceClient.getDefault();
        client.addBinderDeathListener(mBinderDeathListener);
        client.addObserver("*", mEditObserver);
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
        if (EditModeController.isEditModeClosing(this)) {
            showNotification(true);
            return;
        }
        boolean editMode = EditModeController.isEditModeEnabled(this);
        if (intent != null && TextUtils.equals(intent.getAction(), Intent.ACTION_EDIT)) {
            RuleServiceClient client = RuleServiceClient.getDefault();
            if (!client.hasLight()) {
                String reason = client.getServiceFailureMessage();
                Toast.makeText(this, reason == null
                                ? getString(R.string.not_active_module)
                                : getString(R.string.not_active_module_with_reason, reason),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            final boolean requested = !editMode;
            TaskExecutor.executeIo(() -> {
                boolean committed = EditModeController.setEditModeEnabled(this, requested);
                mMainHandler.post(() -> {
                    if (!committed) showToggleFailure();
                    if (isMasterEnabled()) {
                        showNotification(EditModeController.isEditModeEnabled(this));
                    }
                });
            });
        }
        showNotification(editMode);
    }

    private void showToggleFailure() {
        String reason = RuleServiceClient.getDefault().getServiceFailureMessage();
        Toast.makeText(this, reason == null
                ? getString(R.string.edit_mode_update_failed)
                : getString(R.string.edit_mode_update_failed_with_reason, reason),
                Toast.LENGTH_SHORT).show();
    }

    private void createControlChannel() {
        NotificationChannel channel = new NotificationChannel(TAG, "Control panel", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Let there be light");
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private void showNotification(boolean editMode) {
        Notification notification = buildNotification(editMode);
        if (PlatformCapabilities.supportsForegroundServiceType()) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
    }

    private Notification buildNotification(boolean editMode) {
        Intent managerIntent = new Intent(this, SettingsActivity.class);
        PendingIntent managerPendingIntent = PendingIntent.getActivity(this, 0, managerIntent,
                PendingIntent.FLAG_IMMUTABLE);
        Intent intent = new Intent(this, NotificationService.class);
        intent.setAction(Intent.ACTION_EDIT);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, TAG)
                .setSmallIcon(R.drawable.ic_angel_small)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.ic_angel_normal))
                .setContentTitle(getText(R.string.app_name))
                .setContentText(EditModeController.isEditModeClosing(this)
                        ? getString(R.string.edit_mode_closing)
                        : editMode ? getString(R.string.notification_edit_exit)
                        : getString(R.string.notification_edit_start))
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
        RuleServiceClient client = RuleServiceClient.getDefault();
        client.removeObserver("*", mEditObserver);
        client.removeBinderDeathListener(mBinderDeathListener);
        mMainHandler.removeCallbacksAndMessages(null);
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onDestroy();
    }

    public boolean isEditMode() {
        return EditModeController.isEditModeEnabled(this);
    }

    public boolean isMasterEnabled() {
        return EditModeController.isMasterEnabled(this, R.string.pref_key_master);
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
        }
    }
}
