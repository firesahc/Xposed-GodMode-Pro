package com.kaisar.xposed.godmode;

import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

public final class QuickSettingsService extends TileService implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    public void onStartListening() {
        super.onStartListening();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);
        updateTile();
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onClick() {
        if (!GodModeHelper.isMasterEnabled(this, R.string.pref_key_master)) {
            Toast.makeText(this, R.string.master_not_enabled, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean current = GodModeHelper.isEditModeEnabled(this);
        GodModeHelper.setEditModeEnabled(this, !current);
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean active = GodModeHelper.isEditModeEnabled(this);
        int iconRes = active ? R.drawable.ic_angel_normal : R.drawable.ic_angel_disable;
        tile.setIcon(Icon.createWithResource(this, iconRes));
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (TextUtils.equals(key, getString(R.string.pref_key_editor))
                || TextUtils.equals(key, getString(R.string.pref_key_master))) {
            updateTile();
        }
    }
}
