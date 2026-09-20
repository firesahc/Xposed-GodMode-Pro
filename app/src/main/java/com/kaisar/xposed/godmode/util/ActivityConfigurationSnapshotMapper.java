package com.kaisar.xposed.godmode.util;

import android.content.res.Configuration;

import com.kaisar.xposed.godmode.engine.event.ActivityConfigurationSnapshot;

/**
 * Translates the framework configuration at the app boundary.
 *
 * <p>The engine DTO is deliberately only the projection needed by editor
 * resource selection and panel measurement. This adapter owns the Android
 * mapping, while callers remain responsible for lifecycle and rendering
 * policy.</p>
 */
public final class ActivityConfigurationSnapshotMapper {

    private ActivityConfigurationSnapshotMapper() {}

    /** Creates an immutable editor-relevant projection without retaining the framework object. */
    public static ActivityConfigurationSnapshot from(Configuration configuration) {
        if (configuration == null) return null;
        return new ActivityConfigurationSnapshot(
                configuration.orientation,
                configuration.screenWidthDp,
                configuration.screenHeightDp,
                configuration.smallestScreenWidthDp,
                configuration.densityDpi,
                configuration.screenLayout,
                configuration.uiMode,
                configuration.fontScale);
    }

    /**
     * Returns a copied configuration with the editor projection overlaid.
     * Neither the base configuration nor the snapshot is mutated.
     */
    public static Configuration overlay(Configuration base,
            ActivityConfigurationSnapshot snapshot) {
        Configuration result = base == null ? new Configuration() : new Configuration(base);
        if (snapshot == null) return result;
        result.orientation = snapshot.getOrientation();
        result.screenWidthDp = snapshot.getScreenWidthDp();
        result.screenHeightDp = snapshot.getScreenHeightDp();
        result.smallestScreenWidthDp = snapshot.getSmallestScreenWidthDp();
        result.densityDpi = snapshot.getDensityDpi();
        result.screenLayout = snapshot.getScreenLayout();
        result.uiMode = snapshot.getUiMode();
        result.fontScale = snapshot.getFontScale();
        return result;
    }
}
