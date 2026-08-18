package com.kaisar.xposed.godmode.inject;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.inject.hooks.InteractionHooks;
import com.kaisar.xposed.godmode.inject.hooks.LifecycleHooks;
import com.kaisar.xposed.godmode.orchestrator.RuleLifecycleManager;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import de.robv.android.xposed.XposedHelpers;

/**
 * Xposed Hook 注册中心 — 统一管理所有 Hook 的注册。
 * <p>
 * 由 {@link AppInjector} 在注入目标应用时调用。
 * 不持有业务状态，仅负责 Hook 注册。
 */
public final class HookRegistry {

    private static final String TAG = "HookRegistry";
    private static volatile boolean sHooksRegistered;
    private static volatile boolean sResumeHookInstalled;
    private static volatile boolean sCreateHookInstalled;
    private static volatile boolean sPostResumeHookInstalled;
    private static volatile boolean sDestroyHookInstalled;
    private static volatile boolean sTouchHookInstalled;
    private static volatile boolean sKeyHookInstalled;
    private static volatile boolean sEventBusRegistered;

    private HookRegistry() {}

    /**
     * 注册所有 Hook（仅执行一次）。
     * <p>
     * 注册顺序保持与重构前一致：onResume → onCreate → hooks → observer
     */
    public static synchronized HookInstallReport registerAll(XC_LoadPackage.LoadPackageParam lpp,
                                                              Property<Boolean> switchProp) {
        if (sHooksRegistered) {
            return new HookInstallReport(true, sTouchHookInstalled, sKeyHookInstalled, true);
        }

        sResumeHookInstalled |= install("Activity.onResume", () ->
                XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                        new LifecycleHooks.ActivityResumeHook()));
        sCreateHookInstalled |= install("Activity.onCreate", () ->
                XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                        new LifecycleHooks.ActivityCreateHook(switchProp)));

        LifecycleHooks lifecycleHooks = new LifecycleHooks();
        sPostResumeHookInstalled |= install("Activity.onPostResume", () ->
                XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", lifecycleHooks));
        sDestroyHookInstalled |= install("Activity.onDestroy", () ->
                XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", lifecycleHooks));

        boolean coreReady = sResumeHookInstalled && sCreateHookInstalled
                && sPostResumeHookInstalled && sDestroyHookInstalled;
        if (coreReady && !sEventBusRegistered) {
            try {
                ModuleBootstrap.getEventBus().register(RuleLifecycleManager.getInstance());
                sEventBusRegistered = true;
            } catch (Throwable failure) {
                Logger.w(TAG, "RuleLifecycleManager registration failed", failure);
            }
        }

        sTouchHookInstalled |= install("View.dispatchTouchEvent", () -> {
            InteractionHooks.TouchHook hook = new InteractionHooks.TouchHook(
                    ModuleBootstrap.getEditorOrchestrator());
            XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                    MotionEvent.class, hook);
            ModuleBootstrap.getSwitchProp().addOnPropertyChangeListener(
                    ModuleBootstrap.getEditorOrchestrator());
        });
        sKeyHookInstalled |= install("Activity.dispatchKeyEvent", () ->
                XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                        KeyEvent.class,
                        new InteractionHooks.KeyHook(ModuleBootstrap.getEditorOrchestrator())));

        sHooksRegistered = coreReady && sEventBusRegistered;
        Logger.i(TAG, "hook install result: core=" + sHooksRegistered
                + ", touch=" + sTouchHookInstalled + ", key=" + sKeyHookInstalled);
        return new HookInstallReport(sHooksRegistered, sTouchHookInstalled,
                sKeyHookInstalled, sEventBusRegistered);
    }

    private static boolean install(String name, HookInstall action) {
        try {
            action.install();
            return true;
        } catch (Throwable failure) {
            Logger.w(TAG, name + " unavailable; continuing without this hook", failure);
            return false;
        }
    }

    private interface HookInstall { void install() throws Throwable; }

    public static final class HookInstallReport {
        public final boolean coreReady;
        public final boolean touchInstalled;
        public final boolean keyInstalled;
        public final boolean eventBusRegistered;

        HookInstallReport(boolean coreReady, boolean touchInstalled,
                          boolean keyInstalled, boolean eventBusRegistered) {
            this.coreReady = coreReady;
            this.touchInstalled = touchInstalled;
            this.keyInstalled = keyInstalled;
            this.eventBusRegistered = eventBusRegistered;
        }
    }
}
