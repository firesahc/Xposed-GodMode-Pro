package com.kaisar.xposed.godmode.inject.hooks;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;

import com.kaisar.xposed.godmode.engine.event.ActivityConfigurationSnapshot;
import com.kaisar.xposed.godmode.engine.event.ActivityLifecycleEvent;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.inject.ModuleBootstrap;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.ActivityConfigurationSnapshotMapper;
import com.kaisar.xposed.godmode.util.ModuleResources;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Activity 生命周期 Hook 集合 — 单一事件通道。
 * <p>
 * 包含两类 Hook：
 * <ul>
 *   <li>{@link ActivityCreateHook} — 拦截 {@link Activity#onCreate} 注入模块资源后发布
 *       CREATE 事件（display 调度的开关/窗口守卫与 decorView.post 延迟已迁移至
 *       EditorOrchestrator 订阅侧）</li>
 *   <li>自身 {@link XC_MethodHook} — 拦截 {@code onPostResume} / {@code onDestroy} /
 *       {@code onConfigurationChanged} 发布 {@link ActivityLifecycleEvent}
 *      （RESUME / DESTROY / CONFIG_CHANGED）</li>
 * </ul>
 * <p>
 * 注入层只负责把原始 Activity 回调转成事件，不直调 Editor；规则应用与编辑器绑定
 * 分别由 runtime 层的 RuleLifecycleManager 与 EditorOrchestrator 消费事件完成。
 * Hook 内异常只记日志，不抛给宿主。
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
    // ActivityCreateHook — 注入模块资源 + 编辑面板
    // =========================================================================

    /**
     * 拦截 {@link Activity#onCreate} 注入模块资源，随后发布 CREATE 事件。
     * <p>
     * 开关门控、窗口/decorView 守卫与 decorView.post 延迟 display 逻辑已迁移至
     * EditorOrchestrator 订阅侧；此处仅做回调转译。构造器注入 EventBus，
     * 由 HookRegistry 分装时传入 {@link ModuleBootstrap#getEventBus()}。
     */
    public static final class ActivityCreateHook extends XC_MethodHook {
        private final EventBus mEventBus;

        public ActivityCreateHook(EventBus eventBus) {
            if (eventBus == null) throw new IllegalArgumentException("eventBus is required");
            this.mEventBus = eventBus;
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
                mEventBus.post(new ActivityLifecycleEvent(
                        ActivityLifecycleEvent.Type.CREATE, activity));
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
                // Editor 清理由 EditorOrchestrator 订阅 DESTROY 完成（注册顺序保证
                // Editor 先于 RuleLifecycleManager 执行，保持原“先清 Editor 再发 DESTROY”语义）；
                // 此处仅发布事件，不直调 Editor。
                mEventBus.post(new ActivityLifecycleEvent(
                        ActivityLifecycleEvent.Type.DESTROY, activity));
            } else if ("onConfigurationChanged".equals(methodName)) {
                Configuration configuration = param.args != null && param.args.length > 0
                        && param.args[0] instanceof Configuration
                        ? (Configuration) param.args[0] : null;
                ActivityConfigurationSnapshot snapshot =
                        ActivityConfigurationSnapshotMapper.from(configuration);
                if (snapshot == null) {
                    Logger.w(TAG, "configuration change hook missing Configuration argument");
                    return;
                }
                mEventBus.post(new ActivityLifecycleEvent(
                        ActivityLifecycleEvent.Type.CONFIG_CHANGED, activity,
                        snapshot));
            }
        } catch (Throwable failure) {
            Logger.w(TAG, "lifecycle event hook failed", failure);
        }
    }

}
