package com.kaisar.xposed.godmode.engine.event;

import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 轻量级事件总线 — 发布-订阅模式。
 * <p>
 * 使用 ConcurrentHashMap + CopyOnWriteArrayList 保证线程安全，
 * 订阅者通过 WeakReference 持有防止内存泄漏。
 * 每次 post 时自动清理已回收的订阅者。
 */
public final class EventBus {

    private static final EventBus INSTANCE = new EventBus();

    public static EventBus getDefault() {
        return INSTANCE;
    }

    /** 事件类型 → 订阅者列表 */
    private final Map<Class<?>, CopyOnWriteArrayList<SubscriberRef>> mSubscribers
            = new ConcurrentHashMap<>();

    private EventBus() {}

    // ---- 注册/注销 ----

    /**
     * 注册订阅者。扫描所有带 @Subscribe 注解的方法并注册。
     */
    public void register(Object subscriber) {
        if (subscriber == null) return;
        for (Method method : subscriber.getClass().getDeclaredMethods()) {
            Subscribe annotation = method.getAnnotation(Subscribe.class);
            if (annotation == null) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1) continue;
            Class<?> eventType = params[0];
            method.setAccessible(true);
            SubscriberRef ref = new SubscriberRef(subscriber, method);
            mSubscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(ref);
        }
    }

    /**
     * 注销订阅者。移除其所有 @Subscribe 方法。
     */
    public void unregister(Object subscriber) {
        if (subscriber == null) return;
        for (List<SubscriberRef> refs : mSubscribers.values()) {
            refs.removeIf(ref -> ref.get() == subscriber || ref.get() == null);
        }
    }

    // ---- 发布 ----

    /**
     * 向所有匹配事件类型的订阅者发布事件。
     * 订阅者方法在主线程或 post 所在线程同步执行。
     */
    public void post(Object event) {
        if (event == null) return;
        Class<?> eventType = event.getClass();
        List<SubscriberRef> refs = mSubscribers.get(eventType);
        if (refs == null || refs.isEmpty()) return;

        List<SubscriberRef> dead = null;
        for (SubscriberRef ref : refs) {
            Object subscriber = ref.get();
            if (subscriber == null) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(ref);
                continue;
            }
            try {
                ref.method.invoke(subscriber, event);
            } catch (IllegalAccessException | InvocationTargetException e) {
                Log.w("EventBus", "Subscriber " + subscriber.getClass().getSimpleName()
                        + "#" + ref.method.getName() + " threw: "
                        + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
        }
        if (dead != null) {
            refs.removeAll(dead);
        }
    }

    // ---- 内部 ----

    private static final class SubscriberRef extends WeakReference<Object> {
        final Method method;

        SubscriberRef(Object referent, Method method) {
            super(referent);
            this.method = method;
        }
    }
}
