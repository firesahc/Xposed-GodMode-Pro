package com.kaisar.xposed.godmode.inject.hooks;

import android.app.Activity;
import android.os.Bundle;

import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.event.ActivityLifecycleEvent;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.injection.ModuleResources;
import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Activity 生命周期 Hook 集合。
 * <p>
 * 包含三类 Hook：
 * <ul>
 *   <li>{@link ActivityResumeHook} — 拦截 {@link Activity#onResume} 记录当前 Activity</li>
 *   <li>{@link ActivityCreateHook} — 拦截 {@link Activity#onCreate} 注入模块资源 + 编辑面板</li>
 *   <li>自身 {@link XC_MethodHook} — 拦截 {@code onPostResume} / {@code onDestroy}
 *       通过 EventBus 发布 {@link ActivityLifecycleEvent}</li>
 * </ul>
 * <p>
 * Phase 3 从 {@link com.kaisar.xposed.godmode.injection.LifecycleObserver} 提取 XC_MethodHook 角色，
 * 规则应用委托给 LifecycleObserver（Phase 4 → RuleManager）。
 */
public final class LifecycleHooks extends XC_MethodHook {

    private static final String TAG = "LifecycleHooks";
    private final EventBus mEventBus;

    public LifecycleHooks(EventBus eventBus) {
        this.mEventBus = eventBus;
    }

    /** 默认构造器，使用 ModuleBootstrap 的 EventBus */
    public LifecycleHooks() {
        this(ModuleBootstrap.getEventBus());
    }

    // =========================================================================
    // ActivityResumeHook — 记录当前 Activity
    // =========================================================================

    /**
     * 拦截 {@link Activity#onResume} 记录当前 Activity 到 EditorOrchestrator。
     */
    public static final class ActivityResumeHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            EditorOrchestrator orchestrator = ModuleBootstrap.getEditorOrchestrator();
            if (orchestrator != null) {
                orchestrator.setActivity((Activity) param.thisObject);
            }
        }
    }

    // =========================================================================
    // ActivityCreateHook — 注入模块资源 + 编辑面板
    // =========================================================================

    /**
     * 拦截 {@link Activity#onCreate} 注入模块资源。
     * 在编辑器模式下显示编辑面板。
     */
    public static final class ActivityCreateHook extends XC_MethodHook {
        private final Property<Boolean> mSwitchProp;

        public ActivityCreateHook(Property<Boolean> switchProp) {
            this.mSwitchProp = switchProp;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Activity activity = (Activity) param.thisObject;
            ModuleResources.injectInto(activity.getResources());
            if (mSwitchProp.get()) {
                activity.getWindow().getDecorView().post(
                        () -> ModuleBootstrap.getEditorOrchestrator().setDisplay(true));
            }
            super.afterHookedMethod(param);
        }
    }

    // =========================================================================
    // LifecycleHooks 主体 — 处理 onPostResume / onDestroy → EventBus 事件
    // =========================================================================

    @Override
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        super.afterHookedMethod(param);
        Activity activity = (Activity) param.thisObject;
        String methodName = param.method.getName();

        if ("onPostResume".equals(methodName)) {
            mEventBus.post(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.RESUME, activity));
        } else if ("onDestroy".equals(methodName)) {
            mEventBus.post(new ActivityLifecycleEvent(
                    ActivityLifecycleEvent.Type.DESTROY, activity));
        }
    }
}
