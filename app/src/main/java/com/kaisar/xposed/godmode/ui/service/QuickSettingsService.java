package com.kaisar.xposed.godmode.ui.service;

import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.ui.EditModeController;
import com.kaisar.xposed.godmode.ui.EditModeSnapshot;
import com.kaisar.xposed.godmode.util.TaskExecutor;

public final class QuickSettingsService extends TileService implements SharedPreferences.OnSharedPreferenceChangeListener {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final RuleServiceClient.ObserverCallback mEditObserver =
            new RuleServiceClient.ObserverCallback() {
                @Override public void onEditModeChanged(boolean enabled, long editRevision,
                                                        long connectionEpoch) {
                    mMainHandler.post(() -> {
                        if (RuleServiceClient.getDefault().isCurrentEditEvent(
                                connectionEpoch, editRevision)) updateTile();
                    });
                }

                @Override public void onRulesInvalidated(String packageName, long generation,
                                                         long connectionEpoch) { }
            };
    private final Runnable mBinderDeathListener = () -> mMainHandler.post(this::updateTile);

    @Override
    public void onStartListening() {
        super.onStartListening();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
        RuleServiceClient client = RuleServiceClient.getDefault();
        client.addBinderDeathListener(mBinderDeathListener);
        client.addObserver("*", mEditObserver);
        updateTile();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        RuleServiceClient client = RuleServiceClient.getDefault();
        client.removeObserver("*", mEditObserver);
        client.removeBinderDeathListener(mBinderDeathListener);
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onClick() {
        EditModeSnapshot snapshot = EditModeSnapshot.capture(this);
        if (!snapshot.master()) {
            Toast.makeText(this, R.string.master_not_enabled, Toast.LENGTH_SHORT).show();
            return;
        }
        if (snapshot.closing()) {
            Toast.makeText(this, R.string.edit_mode_closing, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean current = snapshot.enabled();
        TaskExecutor.executeIo(() -> {
            boolean committed = EditModeController.setEditModeEnabled(this, !current);
            mMainHandler.post(() -> {
                if (!committed) {
                    String reason = RuleServiceClient.getDefault().getServiceFailureMessage();
                    Toast.makeText(this, reason == null
                            ? getString(R.string.edit_mode_update_failed)
                            : getString(R.string.edit_mode_update_failed_with_reason, reason),
                            Toast.LENGTH_SHORT).show();
                }
                updateTile();
            });
        });
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        EditModeSnapshot snapshot = EditModeSnapshot.capture(this);
        boolean available = snapshot.available();
        boolean active = snapshot.active();
        int iconRes = active ? R.drawable.ic_angel_normal : R.drawable.ic_angel_disable;
        tile.setIcon(Icon.createWithResource(this, iconRes));
        tile.setState(!available ? Tile.STATE_UNAVAILABLE
                : active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (TextUtils.equals(key, getString(R.string.pref_key_master))) {
            updateTile();
        }
    }
}
