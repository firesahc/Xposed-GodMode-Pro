package com.kaisar.xposed.godmode.engine.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class ActivityConfigurationSnapshotTest {

    @Test
    public void equalValuesRepresentTheSameConfiguration() {
        ActivityConfigurationSnapshot first = snapshot(1, 411, 891, 411,
                420, 0x10004, 0x10, 1.0f);
        ActivityConfigurationSnapshot second = snapshot(1, 411, 891, 411,
                420, 0x10004, 0x10, 1.0f);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void orientationAndResourceQualifiersArePartOfIdentity() {
        ActivityConfigurationSnapshot portrait = snapshot(1, 411, 891, 411,
                420, 0x10004, 0x10, 1.0f);
        ActivityConfigurationSnapshot landscape = snapshot(2, 891, 411, 411,
                420, 0x10004, 0x10, 1.0f);

        assertNotEquals(portrait, landscape);
    }

    private static ActivityConfigurationSnapshot snapshot(int orientation,
            int widthDp, int heightDp, int smallestWidthDp, int densityDpi,
            int screenLayout, int uiMode, float fontScale) {
        return new ActivityConfigurationSnapshot(orientation, widthDp, heightDp,
                smallestWidthDp, densityDpi, screenLayout, uiMode, fontScale);
    }
}
