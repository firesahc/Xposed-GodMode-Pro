package com.kaisar.xposed.godmode.engine.event;

import android.app.Activity;

/**
 * Activity 生命周期事件 — 由 LifecycleHooks 发布，由 LifecycleObserver / RuleManager 消费。
 * <p>
 * Phase 3 中 LifecycleObserver 订阅此事件替代原 XC_MethodHook 角色。
 * Phase 4 中 RuleManager 将接管此事件的消费。
 */
public final class ActivityLifecycleEvent {

    public enum Type { RESUME, DESTROY }

    private final Type mType;
    private final Activity mActivity;

    public ActivityLifecycleEvent(Type type, Activity activity) {
        this.mType = type;
        this.mActivity = activity;
    }

    public Type getType() { return mType; }
    public Activity getActivity() { return mActivity; }
}
