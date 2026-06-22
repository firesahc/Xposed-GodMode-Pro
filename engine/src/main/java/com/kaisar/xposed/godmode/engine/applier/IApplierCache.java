package com.kaisar.xposed.godmode.engine.applier;

import java.util.Objects;

/**
 * Applier 缓存接口 — 进程级缓存 Activity 级化隔离的基础抽象。
 * <p>
 * 当前 RemoveApplier 和 ModifyApplier 的实例级缓存使用
 * {@link System#identityHashCode(Object)} 作为键，在 RecyclerView
 * 回收复用时可能跨 Activity 误命中。本接口引入 {@link CacheKey}
 * 将 Activity 标识纳入缓存键，实现 Activity 级别的缓存隔离。
 * <p>
 * 由 {@link RemoveApplier} 和 {@link ModifyApplier} 实现。
 */
public interface IApplierCache {

    /** 存入缓存 */
    void put(CacheKey key, Object value);

    /** 读取缓存 */
    Object get(CacheKey key);

    /** 移除缓存项 */
    void remove(CacheKey key);

    /** 清空所有缓存 */
    void clear();

    /**
     * 复合缓存键：视图标识 + Activity 标识。
     * <p>
     * 两个字段共同构成唯一标识：
     * <ul>
     *   <li>{@link #viewIdentityHash} — {@code System.identityHashCode(view)}，
     *       保证同一 View 对象在 apply 和 revoke 时产生相同键</li>
     *   <li>{@link #activityClassName} — Activity 完整类名，
     *       由 ViewController 构造时注入，确保不同 Activity 的缓存互不污染</li>
     * </ul>
     */
    final class CacheKey {
        public final int viewIdentityHash;
        public final String activityClassName;

        public CacheKey(int viewIdentityHash, String activityClassName) {
            this.viewIdentityHash = viewIdentityHash;
            this.activityClassName = activityClassName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey cacheKey = (CacheKey) o;
            return viewIdentityHash == cacheKey.viewIdentityHash
                    && Objects.equals(activityClassName, cacheKey.activityClassName);
        }

        @Override
        public int hashCode() {
            return 31 * viewIdentityHash + Objects.hashCode(activityClassName);
        }

        @Override
        public String toString() {
            return "CacheKey{hash=" + viewIdentityHash + ", act=" + activityClassName + '}';
        }
    }
}
