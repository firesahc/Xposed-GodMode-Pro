package com.kaisar.xposed.godmode.engine.util;

import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射字段映射器。
 * 用于 app 模块 ViewRule（Parcelable, 带 @SerializedName）与
 * engine 模块 ViewRule（纯 POJO）之间的字段级双向转换。
 * <p>
 * 内部使用 ConcurrentHashMap 缓存 Class → Field[] 映射，避免重复反射开销。
 */
public final class FieldMapper {

    private static final String TAG = "FieldMapper";
    private static final ConcurrentHashMap<Class<?>, Field[]> sFieldCache = new ConcurrentHashMap<>();

    private FieldMapper() {
    }

    /**
     * 获取类的所有字段（包括继承字段），结果被缓存。
     */
    @NonNull
    public static Field[] getFields(@NonNull Class<?> clazz) {
        return sFieldCache.computeIfAbsent(clazz, FieldMapper::collectFields);
    }

    private static Field[] collectFields(Class<?> clazz) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        Field[] result = fields.toArray(new Field[0]);
        for (Field f : result) {
            f.setAccessible(true);
        }
        return result;
    }

    /**
     * 按字段名从源对象拷贝字段值到目标对象。
     * 如果目标对象有同名字段，尝试转换类型后拷贝。
     * 支持基本类型数组的深拷贝。
     *
     * @param source 源对象
     * @param target 目标对象
     * @param <T>    目标类型
     * @return 目标对象（链式调用）
     */
    @NonNull
    public static <T> T copyFields(@NonNull Object source, @NonNull T target) {
        Field[] sourceFields = getFields(source.getClass());
        Field[] targetFields = getFields(target.getClass());

        java.util.Map<String, Field> targetMap = new java.util.HashMap<>();
        for (Field tf : targetFields) {
            targetMap.put(tf.getName(), tf);
        }

        // DEBUG: 检测 source 有但 target 没有的字段（双重 ViewRule 不同步警告）
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Set<String> sourceNames = new HashSet<>();
            for (Field sf : sourceFields) sourceNames.add(sf.getName());
            for (String name : sourceNames) {
                if (!targetMap.containsKey(name)) {
                    Log.d(TAG, "copyFields: source has field '" + name + "' but target '"
                            + target.getClass().getSimpleName() + "' does not (type="
                            + source.getClass().getSimpleName() + ")");
                }
            }
        }

        for (Field sf : sourceFields) {
            Field tf = targetMap.get(sf.getName());
            if (tf == null) continue;
            // 跳过类型不匹配的非同名字段
            if (!isAssignableOrRelated(sf.getType(), tf.getType())) continue;
            try {
                Object value = sf.get(source);
                if (value != null && sf.getType().isArray() && tf.getType().isArray()) {
                    // 数组深拷贝
                    value = arrayDeepCopy(value);
                }
                tf.set(target, value);
            } catch (IllegalAccessException ignored) {
                // 字段不可访问 — 静默跳过
            }
        }
        return target;
    }

    private static boolean isAssignableOrRelated(Class<?> src, Class<?> dst) {
        if (dst.isAssignableFrom(src)) return true;
        // 基本类型自动装箱兼容
        if (src == int.class && dst == Integer.class) return true;
        if (src == long.class && dst == Long.class) return true;
        if (src == float.class && dst == Float.class) return true;
        if (src == double.class && dst == Double.class) return true;
        if (src == boolean.class && dst == Boolean.class) return true;
        if (src == byte.class && dst == Byte.class) return true;
        if (src == short.class && dst == Short.class) return true;
        if (src == char.class && dst == Character.class) return true;
        return false;
    }

    private static Object arrayDeepCopy(Object array) {
        if (array instanceof int[]) return ((int[]) array).clone();
        if (array instanceof long[]) return ((long[]) array).clone();
        if (array instanceof float[]) return ((float[]) array).clone();
        if (array instanceof double[]) return ((double[]) array).clone();
        if (array instanceof boolean[]) return ((boolean[]) array).clone();
        if (array instanceof byte[]) return ((byte[]) array).clone();
        if (array instanceof short[]) return ((short[]) array).clone();
        if (array instanceof char[]) return ((char[]) array).clone();
        if (array instanceof String[]) return ((String[]) array).clone();
        // 对象数组浅拷贝
        if (array instanceof Object[]) return ((Object[]) array).clone();
        return array;
    }

    /** 清空字段缓存（仅在需要释放内存时使用） */
    public static void clearCache() {
        sFieldCache.clear();
    }
}
