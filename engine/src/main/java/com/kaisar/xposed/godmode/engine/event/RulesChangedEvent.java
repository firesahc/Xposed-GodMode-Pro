package com.kaisar.xposed.godmode.engine.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则变更事件。
 * 由应用进程唯一发布端 {@code RuleManager} 通过 EventBus 发布。
 * <p>
 * 携带发布时刻一次算好的显式快照 {@link #oldSnapshot}（发布前）与
 * {@link #newSnapshot}（发布后）：消费方直接用事件自带数据做 diff，
 * 不依赖 EventBus 同步派发顺序去读发布端的活引用。
 * <p>
 * rules 字段声明为 {@code Map<String, ?>} 而非 ActRules，
 * 避免 engine 模块对 app 模块的类型依赖。
 * 消费方（app 模块）在订阅方法中通过转型获取实际类型。
 */
public final class RulesChangedEvent {

    /** 发生规则变更的包名 */
    public final String packageName;

    /**
     * 变更后的规则集合（实际类型为 ActRules，消费方转型获取）。
     * legacy 别名，恒等于 {@link #newSnapshot}，仅为兼容旧调用方保留。
     */
    public final Map<String, ?> rules;

    /** 发布前的旧快照（不可变拷贝，只读）。 */
    public final Map<String, ?> oldSnapshot;

    /** 发布后的新快照（不可变拷贝，只读）。 */
    public final Map<String, ?> newSnapshot;

    /**
     * 主构造：old/new 均在构造处防御拷贝为不可变结构，
     * 发布端内部容器的后续替换不影响已发布事件。
     */
    public RulesChangedEvent(String packageName,
                             Map<String, ?> oldSnapshot,
                             Map<String, ?> newSnapshot) {
        this.packageName = packageName;
        this.oldSnapshot = immutableSnapshot(oldSnapshot);
        this.newSnapshot = immutableSnapshot(newSnapshot);
        this.rules = this.newSnapshot;
    }

    /**
     * 兼容旧单参构造：old 未知时记为空快照，委托主构造。
     * @deprecated 仅为避免一次性改爆所有调用方保留；新代码请用
     *             {@link #RulesChangedEvent(String, Map, Map)} 显式传参。
     */
    @Deprecated
    public RulesChangedEvent(String packageName, Map<String, ?> rules) {
        this(packageName, Collections.emptyMap(), rules);
    }

    /** map + 每项 list 均拷贝为不可变视图；null 归一为空。 */
    private static Map<String, ?> immutableSnapshot(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, Object> copy = new HashMap<>(source.size());
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || value == null) continue;
            if (value instanceof List) {
                copy.put(key, Collections.unmodifiableList(
                        new ArrayList<>((List<?>) value)));
            } else {
                copy.put(key, value);
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
