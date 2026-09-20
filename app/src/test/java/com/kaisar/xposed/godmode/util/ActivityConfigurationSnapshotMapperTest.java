package com.kaisar.xposed.godmode.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import android.content.res.Configuration;

import com.kaisar.xposed.godmode.engine.event.ActivityConfigurationSnapshot;

import org.junit.Test;

/** Verifies the framework-to-engine projection and copy-on-overlay contract. */
public final class ActivityConfigurationSnapshotMapperTest {

    @Test
    public void fromCopiesOnlyTheEditorProjection() {
        Configuration configuration = new Configuration();
        configuration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        configuration.screenWidthDp = 900;
        configuration.screenHeightDp = 600;
        configuration.smallestScreenWidthDp = 600;
        configuration.densityDpi = 420;
        configuration.screenLayout = Configuration.SCREENLAYOUT_SIZE_LARGE;
        configuration.uiMode = Configuration.UI_MODE_NIGHT_YES;
        configuration.fontScale = 1.15f;

        ActivityConfigurationSnapshot snapshot =
                ActivityConfigurationSnapshotMapper.from(configuration);

        assertEquals(configuration.orientation, snapshot.getOrientation());
        assertEquals(configuration.screenWidthDp, snapshot.getScreenWidthDp());
        assertEquals(configuration.screenHeightDp, snapshot.getScreenHeightDp());
        assertEquals(configuration.smallestScreenWidthDp, snapshot.getSmallestScreenWidthDp());
        assertEquals(configuration.densityDpi, snapshot.getDensityDpi());
        assertEquals(configuration.screenLayout, snapshot.getScreenLayout());
        assertEquals(configuration.uiMode, snapshot.getUiMode());
        assertEquals(configuration.fontScale, snapshot.getFontScale(), 0f);
    }

    @Test
    public void overlayCopiesBaseAndDoesNotMutateIt() {
        Configuration base = new Configuration();
        base.orientation = Configuration.ORIENTATION_PORTRAIT;
        base.screenWidthDp = 400;
        base.screenHeightDp = 800;
        base.densityDpi = 320;

        ActivityConfigurationSnapshot snapshot = new ActivityConfigurationSnapshot(
                Configuration.ORIENTATION_LANDSCAPE, 800, 400, 400, 420,
                Configuration.SCREENLAYOUT_SIZE_LARGE, Configuration.UI_MODE_NIGHT_YES, 1.25f);

        Configuration overlaid = ActivityConfigurationSnapshotMapper.overlay(base, snapshot);

        assertNotSame(base, overlaid);
        assertEquals(Configuration.ORIENTATION_PORTRAIT, base.orientation);
        assertEquals(400, base.screenWidthDp);
        assertEquals(800, base.screenHeightDp);
        assertEquals(320, base.densityDpi);
        assertEquals(snapshot.getOrientation(), overlaid.orientation);
        assertEquals(snapshot.getScreenWidthDp(), overlaid.screenWidthDp);
        assertEquals(snapshot.getScreenHeightDp(), overlaid.screenHeightDp);
        assertEquals(snapshot.getDensityDpi(), overlaid.densityDpi);
        assertEquals(snapshot.getUiMode(), overlaid.uiMode);
        assertEquals(snapshot.getFontScale(), overlaid.fontScale, 0f);
    }
}
