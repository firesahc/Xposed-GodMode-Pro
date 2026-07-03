package com.kaisar.xposed.godmode.engine.rule;

import com.kaisar.xposed.godmode.engine.util.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * 规则差集 — 将 LifecycleObserver.computeRuleDiff() 的方法逻辑提升为可复用的纯函数。
 * <p>
 * 计算两个规则集合之间的差集，返回需要撤销的旧规则和需要应用的新规则。
 * 用于 {@code runtime/RuleManager} 在规则变更时驱动 ViewController 的撤销/应用流程。
 * <p>
 * 使用泛型 {@code Map<String, ? extends List<?>>} 避免对 app 模块的类型依赖。
 * 调用方在调用前自行处理类型转换。
 * <p>
 * 不可变对象 — 构造完成后不可修改。
 */
public final class RuleDiff {

    private static final String TAG = "RuleDiff";

    /** 被删除/修改的旧规则（需要在应用新规则前撤销） */
    public final Map<String, List<?>> toRevoke;

    /** 新增/更新后的规则（需要应用） */
    public final Map<String, List<?>> toApply;

    private RuleDiff(Map<String, List<?>> toRevoke, Map<String, List<?>> toApply) {
        this.toRevoke = toRevoke != null
                ? Collections.unmodifiableMap(new HashMap<>(toRevoke))
                : Collections.emptyMap();
        this.toApply = toApply != null
                ? Collections.unmodifiableMap(new HashMap<>(toApply))
                : Collections.emptyMap();
    }

    /**
     * 计算差集 — 使用 {@link Object#equals} 作为规则身份比较。
     * <p>
     * 适用于纯数据比较场景（规则字段为简单值对象）。
     * 对于需要区分"身份相同但内容不同"的场景，使用
     * {@link #compute(Map, Map, BiPredicate, BiPredicate)} 重载。
     *
     * @param oldRules 旧规则集合
     * @param newRules 新规则集合
     * @return 差集结果
     */
    public static RuleDiff compute(
            Map<String, ? extends List<?>> oldRules,
            Map<String, ? extends List<?>> newRules) {
        return computeInternal(oldRules, newRules, Object::equals, null);
    }

    /**
     * 计算差集 — 使用自定义身份比较器，支持内容变更检测。
     * <p>
     * 当规则身份相同但内容不同时：
     * <ul>
     *   <li>旧规则加入 {@link #toRevoke}（需要撤销旧效果）</li>
     *   <li>新规则加入 {@link #toApply}（需要应用新效果）</li>
     * </ul>
     *
     * @param oldRules      旧规则集合
     * @param newRules      新规则集合
     * @param identityEqual 身份相等判断（例如 {@code (a, b) -> a.ruleTag.equals(b.ruleTag)}）
     * @param contentEqual  内容相等判断（例如 {@code (a, b) -> a.contentEquals(b)}）。
     *                      为 null 时退化为 identityEqual 语义（仅身份比较，不检测内容变化）
     * @param <T>           规则元素类型
     * @return 差集结果
     */
    @SuppressWarnings("unchecked")
    public static <T> RuleDiff compute(
            Map<String, List<T>> oldRules,
            Map<String, List<T>> newRules,
            BiPredicate<T, T> identityEqual,
            BiPredicate<T, T> contentEqual) {
        Objects.requireNonNull(identityEqual, "identityEqual must not be null");
        BiPredicate<Object, Object> identityBridge = (a, b) -> {
            try {
                return identityEqual.test((T) a, (T) b);
            } catch (ClassCastException e) {
                return false;
            }
        };
        BiPredicate<Object, Object> contentBridge = contentEqual != null
                ? (a, b) -> {
                    try {
                        return contentEqual.test((T) a, (T) b);
                    } catch (ClassCastException e) {
                        return false;
                    }
                }
                : null;
        return computeInternal(oldRules, newRules, identityBridge, contentBridge);
    }

    /**
     * 检查快照是否与当前缓存有差异。
     * <p>
     * 用于 {@code runtime/RuleManager.refreshFromSnapshot()} 判断是否需要触发规则变更。
     *
     * @param currentCache 当前规则缓存（可为 null）
     * @param snapshot     文件快照
     * @return true 如果快照的 generation 更新或 payload 不同
     */
    public static boolean hasChanged(Map<String, ?> currentCache, RuleSnapshot snapshot) {
        if (currentCache == null) return true;
        if (snapshot == null || snapshot.payload == null) return false;
        return !currentCache.equals(snapshot.payload);
    }

    // ===== 内部实现 =====

    /**
     * 核心差集计算逻辑。
     *
     * 算法：
     * <ol>
     *   <li>遍历 oldRules 的每个 entry</li>
     *   <li>newRules 中不存在同名 key → 整个 entry 加入 toRevoke</li>
     *   <li>newRules 中存在同名 key → 逐条比较</li>
     *   <li>旧有、新无 → toRevoke</li>
     *   <li>新有、旧无 → toApply</li>
     *   <li>旧/新身份相同但内容不同 → 旧规则加入 toRevoke，新规则加入 toApply</li>
     *   <li>旧/新身份相同且内容相同 → 跳过</li>
     * </ol>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RuleDiff computeInternal(
            Map<String, ? extends List<?>> oldRules,
            Map<String, ? extends List<?>> newRules,
            BiPredicate<Object, Object> identityEqual,
            BiPredicate<Object, Object> contentEqual) {

        Map<String, List<?>> revoke = new HashMap<>();
        Map<String, List<?>> apply = new HashMap<>();

        if (oldRules == null || oldRules.isEmpty()) {
            // 旧规则为空 → 全部新规则需要应用
            if (newRules != null) {
                apply.putAll(newRules);
            }
            return new RuleDiff(revoke, apply);
        }

        if (newRules == null || newRules.isEmpty()) {
            // 新规则为空 → 全部旧规则需要撤销
            revoke.putAll(oldRules);
            return new RuleDiff(revoke, apply);
        }

        // 遍历旧规则
        for (Map.Entry<String, ? extends List<?>> oldEntry : oldRules.entrySet()) {
            String key = oldEntry.getKey();
            List<?> oldList = oldEntry.getValue();
            List<?> newList = newRules.get(key);

            if (newList == null || newList.isEmpty()) {
                // 该 activity 在新规则中不存在 → 整个撤销
                if (oldList != null && !oldList.isEmpty()) {
                    revoke.put(key, new ArrayList<>(oldList));
                }
                continue;
            }

            // 新旧都有该 activity → 逐条比较
            List<Object> toRevokeItems = new ArrayList<>();
            List<Object> toApplyItems = new ArrayList<>();

            // 1. 找旧中有但新中无的条目（被删除的规则）
            for (Object oldItem : oldList) {
                boolean found = false;
                boolean contentChanged = false;
                for (Object newItem : newList) {
                    if (identityEqual.test(oldItem, newItem)) {
                        found = true;
                        // 检测内容变化
                        if (contentEqual != null && !contentEqual.test(oldItem, newItem)) {
                            contentChanged = true;
                        }
                        break;
                    }
                }
                if (!found) {
                    // 旧规则在新集合中不存在 → 撤销
                    toRevokeItems.add(oldItem);
                } else if (contentChanged) {
                    // 身份相同但内容不同 → 撤销旧规则
                    toRevokeItems.add(oldItem);
                }
            }

            // 2. 找新中有但旧中无的条目（新增的规则）
            for (Object newItem : newList) {
                boolean found = false;
                boolean contentChanged = false;
                for (Object oldItem : oldList) {
                    if (identityEqual.test(newItem, oldItem)) {
                        found = true;
                        if (contentEqual != null && !contentEqual.test(oldItem, newItem)) {
                            contentChanged = true;
                        }
                        break;
                    }
                }
                if (!found) {
                    // 全新规则 → 应用
                    toApplyItems.add(newItem);
                } else if (contentChanged) {
                    // 身份相同但内容不同 → 应用新规则
                    toApplyItems.add(newItem);
                }
            }

            if (!toRevokeItems.isEmpty()) {
                revoke.put(key, toRevokeItems);
            }
            if (!toApplyItems.isEmpty()) {
                apply.put(key, toApplyItems);
            }
        }

        // 3. 检查 newRules 中全新的 key（旧中没有的 activity）
        for (Map.Entry<String, ? extends List<?>> newEntry : newRules.entrySet()) {
            String key = newEntry.getKey();
            if (!oldRules.containsKey(key)) {
                List<?> newList = newEntry.getValue();
                if (newList != null && !newList.isEmpty()) {
                    apply.put(key, new ArrayList<>(newList));
                }
            }
        }

        return new RuleDiff(revoke, apply);
    }

    // ===== 查询方法 =====

    /** diff 是否为空（无任何变更） */
    public boolean isEmpty() {
        return toRevoke.isEmpty() && toApply.isEmpty();
    }

    /** 是否有需要撤销的规则 */
    public boolean hasRevocations() {
        return !toRevoke.isEmpty();
    }

    /** 是否有需要应用的规则 */
    public boolean hasApplications() {
        return !toApply.isEmpty();
    }

    @Override
    public String toString() {
        return "RuleDiff{"
                + "revoke=" + countEntries(toRevoke)
                + ", apply=" + countEntries(toApply)
                + '}';
    }

    private static int countEntries(Map<String, List<?>> map) {
        int count = 0;
        for (List<?> list : map.values()) {
            if (list != null) count += list.size();
        }
        return count;
    }

    // ===== 工具方法 =====

    /**
     * 合并两个差集（将 other 中的变更合并到当前 diff 中）。
     * <p>
     * 用于将多次独立的规则变更合并为一次批量操作。
     *
     * @param other 另一个差集
     * @return 合并后的新差集
     */
    public RuleDiff merge(RuleDiff other) {
        if (other == null || other.isEmpty()) return this;
        if (this.isEmpty()) return other;

        Map<String, List<?>> mergedRevoke = new HashMap<>(this.toRevoke);
        Map<String, List<?>> mergedApply = new HashMap<>(this.toApply);

        // other 中的 toRevoke 优先于当前 diff 中的 toApply
        for (Map.Entry<String, List<?>> entry : other.toRevoke.entrySet()) {
            mergedRevoke.merge(entry.getKey(), entry.getValue(), (a, b) -> {
                List<Object> merged = new ArrayList<>(a);
                merged.addAll(b);
                return merged;
            });
            mergedApply.remove(entry.getKey());
        }

        for (Map.Entry<String, List<?>> entry : other.toApply.entrySet()) {
            mergedApply.merge(entry.getKey(), entry.getValue(), (a, b) -> {
                List<Object> merged = new ArrayList<>(a);
                merged.addAll(b);
                return merged;
            });
        }

        return new RuleDiff(mergedRevoke, mergedApply);
    }
}
