package com.kaisar.xposed.godmode.engine.event;

import android.app.Activity;

/**
 * Activity 生命周期事件 — 由 LifecycleHooks 发布，由 runtime 层消费。
 * <p>
 * 消费者：
 * <ul>
 *   <li>RuleLifecycleManager — RESUME 时注册布局监听并应用规则，DESTROY 时清理
 *      （忽略 CREATE）</li>
 *   <li>EditorOrchestrator — RESUME 时绑定当前 Activity，CREATE 时按开关调度
 *       延迟 display，DESTROY 时清理编辑会话</li>
 * </ul>
 */
public final class ActivityLifecycleEvent {

    /**
     * 生命周期语义（与 Android 回调 1:1 对应）：
     * <ul>
     *   <li>CREATE — {@code Activity#onCreate} 之后（资源注入完成后发布；
     *       仅携带 Activity，display 调度所需的开关/窗口守卫由订阅侧执行）</li>
     *   <li>RESUME — {@code onPostResume} 之后（唯一发布源；窗口就绪，晚于 onResume）</li>
     *   <li>DESTROY — {@code onDestroy}</li>
     *   <li>CONFIG_CHANGED — {@code Activity#onConfigurationChanged} 之后，
     *       仅 Editor 消费（旋转重建面板），规则层忽略</li>
     * </ul>
     */
    public enum Type { CREATE, RESUME, DESTROY, CONFIG_CHANGED }

    private final Type mType;
    private final Activity mActivity;
    private final ActivityConfigurationSnapshot mConfigurationSnapshot;

    public ActivityLifecycleEvent(Type type, Activity activity) {
        this(type, activity, null);
    }

    public ActivityLifecycleEvent(Type type, Activity activity,
            ActivityConfigurationSnapshot configurationSnapshot) {
        this.mType = type;
        this.mActivity = activity;
        this.mConfigurationSnapshot = configurationSnapshot;
    }

    public Type getType() { return mType; }
    public Activity getActivity() { return mActivity; }
    public ActivityConfigurationSnapshot getConfigurationSnapshot() {
        return mConfigurationSnapshot;
    }
}
