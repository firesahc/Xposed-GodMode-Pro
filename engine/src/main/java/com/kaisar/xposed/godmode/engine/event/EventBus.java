package com.kaisar.xposed.godmode.engine.event;

import com.kaisar.xposed.godmode.engine.util.Logger;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 杞婚噺绾т簨浠舵€荤嚎 鈥?鍙戝竷-璁㈤槄妯″紡銆?
 * <p>
 * 浣跨敤 ConcurrentHashMap + CopyOnWriteArrayList 淇濊瘉绾跨▼瀹夊叏锛?
 * 璁㈤槄鑰呴€氳繃 WeakReference 鎸佹湁闃叉鍐呭瓨娉勬紡銆?
 * 姣忔 post 鏃惰嚜鍔ㄦ竻鐞嗗凡鍥炴敹鐨勮闃呰€呫€?
 */
public final class EventBus {

    private static final EventBus INSTANCE = new EventBus();

    public static EventBus getDefault() {
        return INSTANCE;
    }

    /** 浜嬩欢绫诲瀷 鈫?璁㈤槄鑰呭垪琛?*/
    private final Map<Class<?>, CopyOnWriteArrayList<SubscriberRef>> mSubscribers
            = new ConcurrentHashMap<>();

    private EventBus() {}

    // ---- 娉ㄥ唽/娉ㄩ攢 ----

    /**
     * 娉ㄥ唽璁㈤槄鑰呫€傛壂鎻忔墍鏈夊甫 @Subscribe 娉ㄨВ鐨勬柟娉曞苟娉ㄥ唽銆?
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
     * 娉ㄩ攢璁㈤槄鑰呫€傜Щ闄ゅ叾鎵€鏈?@Subscribe 鏂规硶銆?
     */
    public void unregister(Object subscriber) {
        if (subscriber == null) return;
        for (List<SubscriberRef> refs : mSubscribers.values()) {
            refs.removeIf(ref -> ref.get() == subscriber || ref.get() == null);
        }
    }

    // ---- 鍙戝竷 ----

    /**
     * 鍚戞墍鏈夊尮閰嶄簨浠剁被鍨嬬殑璁㈤槄鑰呭彂甯冧簨浠躲€?
     * 璁㈤槄鑰呮柟娉曞湪涓荤嚎绋嬫垨 post 鎵€鍦ㄧ嚎绋嬪悓姝ユ墽琛屻€?
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
                Logger\.w\("EventBus",\ "Subscriber\ " + subscriber.getClass().getSimpleName()
                        + "#" + ref.method.getName() + " threw: "
                        + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
        }
        if (dead != null) {
            refs.removeAll(dead);
        }
    }

    // ---- 鍐呴儴 ----

    private static final class SubscriberRef extends WeakReference<Object> {
        final Method method;

        SubscriberRef(Object referent, Method method) {
            super(referent);
            this.method = method;
        }
    }
}
