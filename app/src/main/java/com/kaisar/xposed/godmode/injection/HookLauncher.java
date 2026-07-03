package com.kaisar.xposed.godmode.injection;

import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;
import com.kaisar.xposed.godmode.rule.ActRules;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * @deprecated 已迁移到 {@link ModuleBootstrap}。
 * Xposed 入口已改为 {@code com.kaisar.xposed.godmode.inject.ModuleBootstrap}（见 xposed_init）。
 * 此类仅作为向后兼容的委托壳，将在 Phase 6 清理时移除。
 */
@Deprecated
public final class HookLauncher implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "HookLauncher";

    private final ModuleBootstrap mDelegate = new ModuleBootstrap();

    // =========================================================================
    // 委托给 ModuleBootstrap 的静态 getter
    // =========================================================================

    /** @deprecated 委托到 {@link ModuleBootstrap#getEditorOrchestrator()} */
    @Deprecated
    public static EditorOrchestrator getEditorOrchestrator() {
        return ModuleBootstrap.getEditorOrchestrator();
    }

    /** @deprecated 委托到 {@link ModuleBootstrap#getSwitchProp()} */
    @Deprecated
    public static Property<Boolean> getSwitchProp() {
        return ModuleBootstrap.getSwitchProp();
    }

    /** @deprecated 委托到 {@link ModuleBootstrap#isSwitchEnabled()} */
    @Deprecated
    public static boolean isSwitchEnabled() {
        return ModuleBootstrap.isSwitchEnabled();
    }

    /** @deprecated 委托到 {@link ModuleBootstrap#getLoadPackageParam()} */
    @Deprecated
    public static XC_LoadPackage.LoadPackageParam getLoadPackageParam() {
        return ModuleBootstrap.getLoadPackageParam();
    }

    /** @deprecated 委托到 {@link ModuleBootstrap#getPackageName()} */
    @Deprecated
    public static String getPackageName() {
        return ModuleBootstrap.getPackageName();
    }

    // =========================================================================
    // 委托静态方法
    // =========================================================================

    /** @deprecated 委托到 {@link ModuleBootstrap#notifyEditModeChanged(boolean)} */
    @Deprecated
    public static void notifyEditModeChanged(boolean enable) {
        ModuleBootstrap.notifyEditModeChanged(enable);
    }

    /** @deprecated 委托到 {@link ModuleBootstrap#notifyViewRulesChanged(ActRules)} */
    @Deprecated
    public static void notifyViewRulesChanged(ActRules actRules) {
        ModuleBootstrap.notifyViewRulesChanged(actRules);
    }

    // =========================================================================
    // IXposedHook LoadPackage / ZygoteInit 委托
    // =========================================================================

    @Override
    public void initZygote(StartupParam startupParam) {
        Logger.w(TAG, "[Deprecated] HookLauncher.initZygote called — delegate to ModuleBootstrap");
        mDelegate.initZygote(startupParam);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        Logger.w(TAG, "[Deprecated] HookLauncher.handleLoadPackage called — delegate to ModuleBootstrap");
        mDelegate.handleLoadPackage(lpp);
    }
}
