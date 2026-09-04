package com.kaisar.xposed.godmode.inject.hooks;

import android.app.Activity;
import android.os.Bundle;

import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.event.ActivityLifecycleEvent;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.ModuleResources;
import com.kaisar.xposed.godmode.editor.EditorOrchestrator;

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
 * 注入层只负责把原始 Activity 回调转成事件；规则应用由 runtime 层的
 * RuleLifecycleManager 消费事件并完成。
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
            try {
                EditorOrchestrator orchestrator = ModuleBootstrap.getEditorOrchestrator();
                if (orchestrator != null && param.thisObject instanceof Activity) {
                    orchestrator.setActivity((Activity) param.thisObject);
                }
            } catch (Throwable failure) {
                Logger.w(TAG, "editor activity update failed", failure);
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
        protected void afterHookedMethod(MethodHookParam param) {
            Activity activity = param.thisObject instanceof Activity
                    ? (Activity) param.thisObject : null;
            if (activity == null) return;
            try {
                ModuleResources.injectInto(activity.getResources());
            } catch (Throwable failure) {
                Logger.w(TAG, "module resource injection failed", failure);
            }
            try {
                if (mSwitchProp != null && Boolean.TRUE.equals(mSwitchProp.get())
                        && activity.getWindow() != null
                        && activity.getWindow().getDecorView() != null) {
                    activity.getWindow().getDecorView().post(() -> {
                        try {
                            EditorOrchestrator orchestrator =
                                    ModuleBootstrap.getEditorOrchestrator();
                            if (orchestrator != null) {
                                orchestrator.setDisplayForActivity(activity, true);
                            }
                        } catch (Throwable failure) {
                            Logger.w(TAG, "deferred editor display failed", failure);
                        }
                    });
                }
            } catch (Throwable failure) {
                Logger.w(TAG, "editor display scheduling failed", failure);
            }
            try {
                super.afterHookedMethod(param);
            } catch (Throwable failure) {
                Logger.w(TAG, "ActivityCreateHook completion failed", failure);
            }
        }
    }

    // =========================================================================
    // LifecycleHooks 主体 — 处理 onPostResume / onDestroy → EventBus 事件
    // =========================================================================

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        try {
            super.afterHookedMethod(param);
            if (!(param.thisObject instanceof Activity) || param.method == null) return;
            Activity activity = (Activity) param.thisObject;
            String methodName = param.method.getName();

            if ("onPostResume".equals(methodName)) {
                mEventBus.post(new ActivityLifecycleEvent(
                        ActivityLifecycleEvent.Type.RESUME, activity));
            } else if ("onDestroy".equals(methodName)) {
                try {
                    EditorOrchestrator orchestrator = ModuleBootstrap.getEditorOrchestrator();
                    if (orchestrator != null) orchestrator.onActivityDestroyed(activity);
                } catch (Throwable failure) {
                    Logger.w(TAG, "editor activity destroy failed", failure);
                }
                mEventBus.post(new ActivityLifecycleEvent(
                        ActivityLifecycleEvent.Type.DESTROY, activity));
            }
        } catch (Throwable failure) {
            Logger.w(TAG, "lifecycle event hook failed", failure);
        }
    }
}
