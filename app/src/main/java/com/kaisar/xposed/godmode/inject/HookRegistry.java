package com.kaisar.xposed.godmode.inject;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.editor.EditorInteractionPort;
import com.kaisar.xposed.godmode.inject.hooks.InteractionHooks;
import com.kaisar.xposed.godmode.inject.hooks.LifecycleHooks;
import com.kaisar.xposed.godmode.orchestrator.RepeatableRuleGate;

import de.robv.android.xposed.XposedHelpers;

/**
 * Xposed Hook 注册中心 — 统一管理所有 Hook 的注册。
 * <p>
 * 由 {@link AppInjector} 在注入目标应用时调用。
 * 不持有业务状态，仅负责 Hook 注册。
 */
public final class HookRegistry implements RepeatableRuleGate {

    private static final String TAG = "HookRegistry";
    private static volatile boolean sHooksRegistered;
    private static volatile boolean sCreateHookInstalled;
    private static volatile boolean sPostResumeHookInstalled;
    private static volatile boolean sDestroyHookInstalled;
    private static volatile boolean sTouchHookInstalled;
    private static volatile boolean sKeyHookInstalled;
    private static volatile boolean sEventBusRegistered;
    private static volatile boolean sEditorEnabled;
    private static volatile boolean sRepeatableRulesEnabled;

    private HookRegistry() {}

    /** Gate 单例 — 由 AppInjector 装配给 orchestrator 层，避免静态跨包调用。 */
    private static final HookRegistry sGateInstance = new HookRegistry();

    /** 获取 repeatable 门控实例（inject 层唯一实现）。 */
    public static RepeatableRuleGate getGate() {
        return sGateInstance;
    }

    /**
     * Registers all hooks independently. A failed optional hook can be retried
     * without reinstalling hooks that already succeeded.
     */
    public static synchronized HookInstallReport registerAll(Property<Boolean> switchProp) {
        if (switchProp != null) sEditorEnabled = switchProp.get();

        if (!sCreateHookInstalled) {
            sCreateHookInstalled = install("Activity.onCreate", () ->
                    XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                            new LifecycleHooks.ActivityCreateHook(switchProp)));
        }

        LifecycleHooks lifecycleHooks = new LifecycleHooks();
        if (!sPostResumeHookInstalled) {
            sPostResumeHookInstalled = install("Activity.onPostResume", () ->
                    XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", lifecycleHooks));
        }
        if (!sDestroyHookInstalled) {
            sDestroyHookInstalled = install("Activity.onDestroy", () ->
                    XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", lifecycleHooks));
        }

        // P1.5-A: onResume 通道已合并至 onPostResume（唯一 RESUME 发布源），此处不再安装。
        boolean coreReady = sCreateHookInstalled
                && sPostResumeHookInstalled && sDestroyHookInstalled;
        // P0-1: EventBus 注册已迁移至 AppInjector，此处不再触碰 orchestrator 层，
        // 避免 inject <-> orchestrator 双向循环。coreReady 仅反映物理 Hook 状态。

        if (!sTouchHookInstalled) {
            sTouchHookInstalled = install("View.dispatchTouchEvent", () -> {
                InteractionHooks.TouchHook hook = new InteractionHooks.TouchHook(
                        (EditorInteractionPort) ModuleBootstrap.getEditorOrchestrator());
                XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                        MotionEvent.class, hook);
                ModuleBootstrap.getSwitchProp().addOnPropertyChangeListener(
                        ModuleBootstrap.getEditorOrchestrator());
            });
        }
        if (!sKeyHookInstalled) {
            sKeyHookInstalled = install("Activity.dispatchKeyEvent", () ->
                    XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                            KeyEvent.class,
                            new InteractionHooks.KeyHook(
                                    (EditorInteractionPort) ModuleBootstrap.getEditorOrchestrator())));
        }

        sHooksRegistered = coreReady;
        Logger.i(TAG, "hook install result: core=" + sHooksRegistered
                + ", touch=" + sTouchHookInstalled + ", key=" + sKeyHookInstalled);
        return new HookInstallReport(sHooksRegistered, sTouchHookInstalled,
                sKeyHookInstalled, sEventBusRegistered);
    }

    /** Updates the editor business gate without changing the physical hook. */
    public static void setEditorEnabled(boolean enabled) {
        sEditorEnabled = enabled;
    }

    public static boolean isEditorEnabled() {
        return sEditorEnabled;
    }

    /** Updates the repeatable-rule business gate without changing hooks. */
    @Override
    public void setRepeatableRulesEnabled(boolean enabled) {
        sRepeatableRulesEnabled = enabled;
    }

    @Override
    public boolean isRepeatableRulesEnabled() {
        return sRepeatableRulesEnabled;
    }

    /** 由 AppInjector 在 EventBus 注册成功后调用，保持诊断口径一致。 */
    public static void setEventBusRegistered(boolean registered) {
        sEventBusRegistered = registered;
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
