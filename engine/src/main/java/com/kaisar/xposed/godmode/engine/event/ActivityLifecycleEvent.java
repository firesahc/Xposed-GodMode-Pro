package com.kaisar.xposed.godmode.engine.event;

import android.app.Activity;

/**
 * Activity 生命周期事件 — 由 LifecycleHooks 发布，由 runtime 层消费。
 * <p>
 * 当前消费者为 RuleLifecycleManager，负责规则应用和 Activity 级缓存维护。
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
