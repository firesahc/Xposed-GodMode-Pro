package com.kaisar.xposed.godmode.engine.event;

import java.util.Objects;

/** Immutable, Android-independent configuration data carried by lifecycle events. */
public final class ActivityConfigurationSnapshot {

    private final int mOrientation;
    private final int mScreenWidthDp;
    private final int mScreenHeightDp;
    private final int mSmallestScreenWidthDp;
    private final int mDensityDpi;
    private final int mScreenLayout;
    private final int mUiMode;
    private final float mFontScale;

    public ActivityConfigurationSnapshot(int orientation, int screenWidthDp,
            int screenHeightDp, int smallestScreenWidthDp, int densityDpi,
            int screenLayout, int uiMode, float fontScale) {
        mOrientation = orientation;
        mScreenWidthDp = screenWidthDp;
        mScreenHeightDp = screenHeightDp;
        mSmallestScreenWidthDp = smallestScreenWidthDp;
        mDensityDpi = densityDpi;
        mScreenLayout = screenLayout;
        mUiMode = uiMode;
        mFontScale = fontScale;
    }

    public int getOrientation() { return mOrientation; }
    public int getScreenWidthDp() { return mScreenWidthDp; }
    public int getScreenHeightDp() { return mScreenHeightDp; }
    public int getSmallestScreenWidthDp() { return mSmallestScreenWidthDp; }
    public int getDensityDpi() { return mDensityDpi; }
    public int getScreenLayout() { return mScreenLayout; }
    public int getUiMode() { return mUiMode; }
    public float getFontScale() { return mFontScale; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ActivityConfigurationSnapshot)) return false;
        ActivityConfigurationSnapshot that = (ActivityConfigurationSnapshot) other;
        return mOrientation == that.mOrientation
                && mScreenWidthDp == that.mScreenWidthDp
                && mScreenHeightDp == that.mScreenHeightDp
                && mSmallestScreenWidthDp == that.mSmallestScreenWidthDp
                && mDensityDpi == that.mDensityDpi
                && mScreenLayout == that.mScreenLayout
                && mUiMode == that.mUiMode
                && Float.compare(mFontScale, that.mFontScale) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mOrientation, mScreenWidthDp, mScreenHeightDp,
                mSmallestScreenWidthDp, mDensityDpi, mScreenLayout, mUiMode,
                mFontScale);
    }

    @Override
    public String toString() {
        return "ActivityConfigurationSnapshot{"
                + "orientation=" + mOrientation
                + ", screenWidthDp=" + mScreenWidthDp
                + ", screenHeightDp=" + mScreenHeightDp
                + ", smallestScreenWidthDp=" + mSmallestScreenWidthDp
                + ", densityDpi=" + mDensityDpi
                + ", screenLayout=" + mScreenLayout
                + ", uiMode=" + mUiMode
                + ", fontScale=" + mFontScale
                + '}';
    }
}
