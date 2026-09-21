package com.kaisar.xposed.godmode.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.event.ActivityConfigurationSnapshot;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
import com.kaisar.xposed.godmode.engine.util.Logger;

/**
 * Module UI resource boundary.
 *
 * <p>Every panel render receives its own resource context and immutable
 * configuration snapshot. The bootstrap value is only an AssetManager source
 * for the exceptional hand-built fallback; no panel reads a mutable global
 * {@link Resources} instance.</p>
 */
public final class GmResources {

    private static final String TAG = "GmResources";
    private static final String MODULE_PACKAGE = BuildConfig.APPLICATION_ID;

    /** Module asset source retained by bootstrap; configuration is per render. */
    private static AssetManager sModuleAssets;

    private GmResources() {}

    public static void init(Resources moduleRes) {
        sModuleAssets = moduleRes == null ? null : moduleRes.getAssets();
    }

    /** The complete resource/theme/configuration owner for one panel render. */
    public static final class UiContext {
        private final Context mContext;
        private final Resources mResources;
        private final ActivityConfigurationSnapshot mConfigurationSnapshot;
        private final boolean mFallback;

        private UiContext(Context context, Resources resources,
                ActivityConfigurationSnapshot configurationSnapshot, boolean fallback) {
            mContext = context;
            mResources = resources;
            mConfigurationSnapshot = configurationSnapshot;
            mFallback = fallback;
        }

        public Context getContext() { return mContext; }
        public Resources getResources() { return mResources; }
        public ActivityConfigurationSnapshot getConfigurationSnapshot() {
            return mConfigurationSnapshot;
        }
        public boolean isFallback() { return mFallback; }
    }

    /** Capture the host configuration without exposing Android types to callers. */
    public static ActivityConfigurationSnapshot captureConfiguration(Activity activity) {
        if (activity == null) return null;
        try {
            return ActivityConfigurationSnapshotMapper.from(
                    activity.getResources().getConfiguration());
        } catch (RuntimeException failure) {
            Logger.d(TAG, "host configuration unavailable", failure);
            return null;
        }
    }

    /** Create a UI context using the Activity's current configuration. */
    public static UiContext createUiContext(Activity activity) {
        return createUiContext(activity, captureConfiguration(activity));
    }

    /** Create a UI context using an explicit lifecycle configuration snapshot. */
    public static UiContext createUiContext(Activity activity,
            ActivityConfigurationSnapshot snapshot) {
        if (activity == null) throw new IllegalArgumentException("activity is required");
        Configuration effectiveConfiguration = effectiveConfiguration(activity, snapshot);
        ActivityConfigurationSnapshot renderedSnapshot =
                ActivityConfigurationSnapshotMapper.from(effectiveConfiguration);

        Context packageContext = null;
        try {
            packageContext = activity.createPackageContext(MODULE_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException
                | SecurityException
                | Resources.NotFoundException
                | IllegalArgumentException
                | UnsupportedOperationException first) {
            Logger.d(TAG, "ui context stage=PACKAGE_CONTEXT flags=0 failed"
                    + ", continue with IGNORE_SECURITY", first);
        }
        if (packageContext != null) {
            try {
                return configuredUiContext(packageContext, effectiveConfiguration,
                        renderedSnapshot, false);
            } catch (Resources.NotFoundException
                    | SecurityException
                    | IllegalArgumentException
                    | UnsupportedOperationException firstConfiguredFailure) {
                Logger.d(TAG, "ui context stage=CONFIGURED_CONTEXT flags=0 failed"
                        + ", continue with IGNORE_SECURITY", firstConfiguredFailure);
            }
        }

        packageContext = null;
        try {
            packageContext = activity.createPackageContext(
                    MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException
                | SecurityException
                | Resources.NotFoundException
                | IllegalArgumentException
                | UnsupportedOperationException second) {
            Logger.d(TAG, "ui context stage=PACKAGE_CONTEXT flags=IGNORE_SECURITY failed"
                    + ", hand-built fallback", second);
        }
        if (packageContext != null) {
            try {
                return configuredUiContext(packageContext, effectiveConfiguration,
                        renderedSnapshot, false);
            } catch (Resources.NotFoundException
                    | SecurityException
                    | IllegalArgumentException
                    | UnsupportedOperationException secondConfiguredFailure) {
                Logger.d(TAG, "ui context stage=CONFIGURED_CONTEXT flags=IGNORE_SECURITY failed"
                        + ", hand-built fallback", secondConfiguredFailure);
            }
        }

        if (sModuleAssets == null) {
            throw new IllegalStateException("module resources not ready");
        }
        return createConfiguredAssetFallback(activity, effectiveConfiguration, renderedSnapshot);
    }

    /**
     * Legacy API 29-compatible resource fallback. Keep this isolated so it
     * cannot become the normal package-context rendering path.
     */
    @SuppressWarnings("deprecation")
    private static UiContext createConfiguredAssetFallback(Activity activity,
            Configuration effectiveConfiguration,
            ActivityConfigurationSnapshot renderedSnapshot) {
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            metrics.setTo(activity.getResources().getDisplayMetrics());
            Resources configuredResources = new Resources(sModuleAssets, metrics,
                    new Configuration(effectiveConfiguration));
            Context moduleContext = new ModuleContext(activity, configuredResources);
            Context themedContext = themedContext(moduleContext);
            UiContext result = new UiContext(themedContext, configuredResources,
                    renderedSnapshot, true);
            Logger.d(TAG, "configuration-scoped module resource fallback succeeded"
                    + " source=CONFIGURED_ASSET_FALLBACK"
                    + " orientation=" + (renderedSnapshot == null
                            ? "unknown" : renderedSnapshot.getOrientation()));
            return result;
        } catch (Resources.NotFoundException
                | SecurityException
                | IllegalArgumentException
                | UnsupportedOperationException fallbackFailure) {
            Logger.e(TAG, "ui context stage=CONFIGURED_ASSET_FALLBACK failed",
                    fallbackFailure);
            throw new IllegalStateException("module UI resources unavailable", fallbackFailure);
        }
    }

    private static UiContext configuredUiContext(Context packageContext,
            Configuration effectiveConfiguration,
            ActivityConfigurationSnapshot renderedSnapshot,
            boolean fallback) {
        Context configured;
        try {
            configured = packageContext.createConfigurationContext(
                    new Configuration(effectiveConfiguration));
        } catch (Resources.NotFoundException
                | SecurityException
                | IllegalArgumentException
                | UnsupportedOperationException failure) {
            Logger.d(TAG, "ui context stage=CONFIGURATION_CONTEXT failed", failure);
            throw failure;
        }
        try {
            Context themed = themedContext(configured);
            return new UiContext(themed, themed.getResources(), renderedSnapshot, fallback);
        } catch (Resources.NotFoundException
                | SecurityException
                | IllegalArgumentException
                | UnsupportedOperationException failure) {
            Logger.d(TAG, "ui context stage=THEME_CONTEXT failed", failure);
            throw failure;
        }
    }

    private static Context themedContext(Context base) {
        return new android.view.ContextThemeWrapper(base, R.style.GmPanelTheme);
    }

    private static Configuration effectiveConfiguration(Activity activity,
            ActivityConfigurationSnapshot snapshot) {
        Configuration configuration;
        try {
            configuration = new Configuration(activity.getResources().getConfiguration());
        } catch (RuntimeException failure) {
            Logger.d(TAG, "host configuration unavailable, use empty base", failure);
            configuration = new Configuration();
        }
        return ActivityConfigurationSnapshotMapper.overlay(configuration, snapshot);
    }

    /** Module resource Context used only by the configuration-scoped fallback. */
    private static final class ModuleContext extends ContextWrapper {
        private final Resources mModuleResources;

        ModuleContext(Context base, Resources moduleResources) {
            super(base);
            mModuleResources = moduleResources;
        }

        @Override
        public Resources getResources() { return mModuleResources; }

        @Override
        public AssetManager getAssets() { return mModuleResources.getAssets(); }
    }

    /**
     * Inflate from the current UiContext. If module inflation fails with a
     * known compatibility exception, the same module parser is handed to the
     * host inflater; it is not re-resolved through a global resource.
     */
    public static View inflate(UiContext uiContext, Activity hostActivity,
            int layoutId, ViewGroup parent, boolean attach) {
        if (uiContext == null) throw new IllegalArgumentException("uiContext is required");
        try {
            return LayoutInflater.from(uiContext.getContext()).inflate(
                    uiContext.getResources().getLayout(layoutId), parent, attach);
        } catch (RuntimeException moduleFailure) {
            if (!isInflationCompatibilityFailure(moduleFailure)) throw moduleFailure;
            Logger.w(TAG, "module inflate failed, host fallback"
                    + " activity=" + (hostActivity == null
                            ? "null" : hostActivity.getClass().getName())
                    + " layout=0x" + Integer.toHexString(layoutId)
                    + " source=" + (uiContext.isFallback()
                            ? "CONFIGURED_ASSET_FALLBACK" : "PACKAGE"), moduleFailure);
            try {
                if (hostActivity == null) throw moduleFailure;
                XmlResourceParser parser = uiContext.getResources().getLayout(layoutId);
                return LayoutInflater.from(hostActivity).inflate(parser, parent, attach);
            } catch (RuntimeException hostFailure) {
                hostFailure.addSuppressed(moduleFailure);
                throw hostFailure;
            }
        }
    }

    private static boolean isInflationCompatibilityFailure(Throwable t) {
        while (t != null) {
            if (t instanceof Resources.NotFoundException
                    || t instanceof android.view.InflateException
                    || t instanceof UnsupportedOperationException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** Mark the complete injected view tree so engine traversal skips it. */
    public static void markAsGmComponent(View root) {
        if (root == null) return;
        root.setTag(GmConstants.TAG_GM_CMP);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                markAsGmComponent(group.getChildAt(i));
            }
        }
    }

    /** UI strings are resolved through the current Activity configuration. */
    public static CharSequence getUiText(Activity activity, int id)
            throws Resources.NotFoundException {
        return createUiContext(activity).getResources().getText(id);
    }

    public static String getUiString(Activity activity, int id)
            throws Resources.NotFoundException {
        return createUiContext(activity).getResources().getString(id);
    }

    public static String getUiString(Activity activity, int id, Object... formatArgs)
            throws Resources.NotFoundException {
        return createUiContext(activity).getResources().getString(id, formatArgs);
    }
}
