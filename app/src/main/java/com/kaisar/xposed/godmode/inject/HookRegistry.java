package com.kaisar.xposed.godmode.inject;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.inject.hooks.DebugHooks;
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

    private HookRegistry() {}

    /**
     * 注册所有 Hook（仅执行一次）。
     * <p>
     * 注册顺序保持与重构前一致：onResume → onCreate → hooks → observer
     */
    public static synchronized void registerAll(XC_LoadPackage.LoadPackageParam lpp,
                                                Property<Boolean> switchProp) {
        if (sHooksRegistered) {
            Logger.d(TAG, "hooks already registered, skipping");
            return;
        }

        // 1) Activity.onResume — 记录当前 Activity
        XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                new LifecycleHooks.ActivityResumeHook());

        // 2) Activity.onCreate — 注入模块资源 + 编辑面板显示
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                new LifecycleHooks.ActivityCreateHook(switchProp));

        // 3) Activity 生命周期事件 Hook (onPostResume + onDestroy)
        // LifecycleHooks 作为 XC_MethodHook 接收原始事件并通过 EventBus 转发
        LifecycleHooks lifecycleHooks = new LifecycleHooks();
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", lifecycleHooks);
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", lifecycleHooks);
        // 注册 RuleLifecycleManager 到 EventBus，消费 ActivityLifecycleEvent 和 RulesChangedEvent
        ModuleBootstrap.getEventBus().register(RuleLifecycleManager.getInstance());

        // 4) 调试布局模式 Hook
        DebugHooks.install(switchProp);

        // 5) 触摸事件 Hook
        InteractionHooks.TouchHook touchHook =
                new InteractionHooks.TouchHook(ModuleBootstrap.getEditorOrchestrator());
        ModuleBootstrap.getSwitchProp().addOnPropertyChangeListener(
                ModuleBootstrap.getEditorOrchestrator());
        XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                MotionEvent.class, touchHook);

        // 6) 按键事件 Hook
        InteractionHooks.KeyHook keyHook =
                new InteractionHooks.KeyHook(ModuleBootstrap.getEditorOrchestrator());
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                KeyEvent.class, keyHook);

        sHooksRegistered = true;
        Logger.d(TAG, "all hooks registered successfully");
    }
}
