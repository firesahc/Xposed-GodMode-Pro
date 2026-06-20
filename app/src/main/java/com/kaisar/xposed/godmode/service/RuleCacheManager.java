package com.kaisar.xposed.godmode.service;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则缓存管理器 — 基于 synchronized 的线程安全内存缓存。
 * 由 RuleServiceServer 使用。
 */
final class RuleCacheManager {

    private final AppRules mAppRulesCache = new AppRules();
    private final Gson mGson;
    private final Logger mLogger;

    RuleCacheManager(Gson gson, Logger logger) {
        this.mGson = gson;
        this.mLogger = logger;
    }

    // ---- 缓存查询 ----

    /** 返回所有规则的防御性副本 */
    AppRules getAllRules() {
        synchronized (mAppRulesCache) {
            AppRules copy = new AppRules();
            for (Map.Entry<String, ActRules> entry : mAppRulesCache.entrySet()) {
                copy.put(entry.getKey(), snapshotActRules(entry.getValue()));
            }
            return copy;
        }
    }

    /** 返回指定包的规则副本（不存在则返回空 ActRules） */
    ActRules getRules(String packageName) {
        synchronized (mAppRulesCache) {
            return mAppRulesCache.containsKey(packageName)
                    ? snapshotActRules(mAppRulesCache.get(packageName)) : new ActRules();
        }
    }

    /** 批量放入规则到缓存（线程安全） */
    void putAll(Map<String, ActRules> appRules) {
        synchronized (mAppRulesCache) {
            mAppRulesCache.putAll(appRules);
        }
        mLogger.d("app rules cache=" + mAppRulesCache.size());
    }

    int size() {
        synchronized (mAppRulesCache) {
            return mAppRulesCache.size();
        }
    }

    // ---- 规则 CRUD 操作 ----

    /**
     * 在规则列表中查找与 target 匹配的索引，未找到返回 -1。
     */
    private static int findRuleIndex(@NonNull List<RuleRecord> rules, @NonNull RuleRecord target) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).isSameViewAs(target)) return i;
        }
        return -1;
    }

    /**
     * 查询某规则在缓存中的旧 imagePath（读操作，不修改缓存）。
     * 供 WorkflowOrchestrator 在持久化前获取旧截图路径用于清理。
     *
     * @param packageName 包名
     * @param viewRule    目标规则
     * @return 旧 imagePath，缓存中不存在或没有 imagePath 时返回 null
     */
    String getOldImagePath(String packageName, RuleRecord viewRule) {
        synchronized (mAppRulesCache) {
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules == null) return null;
            List<RuleRecord> rules = actRules.get(viewRule.activityClass);
            if (rules == null) return null;
            int idx = findRuleIndex(rules, viewRule);
            return idx >= 0 ? rules.get(idx).imagePath : null;
        }
    }

    /**
     * 将规则应用（新增或更新）到缓存中。
     * 供 writeRule / updateRule 使用。
     */
    CacheResult applyRuleToCache(String packageName, RuleRecord viewRule, boolean captureOldImagePath) {
        synchronized (mAppRulesCache) {
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules == null) {
                mAppRulesCache.put(packageName, actRules = new ActRules());
            }
            List<RuleRecord> viewRules = actRules.computeIfAbsent(viewRule.activityClass, k -> new ArrayList<>());
            int index = findRuleIndex(viewRules, viewRule);
            String oldImagePath = null;
            if (index >= 0) {
                if (captureOldImagePath) {
                    oldImagePath = viewRules.get(index).imagePath;
                }
                // 合并：保留旧记录中新增规则未提供的非空字段（如 alias），防止字段丢失
                RuleRecord existing = viewRules.get(index);
                if (viewRule.alias == null && existing.alias != null) {
                    viewRule.alias = existing.alias;
                }
                viewRules.set(index, viewRule);
            } else {
                viewRules.add(viewRule);
            }
            String json = mGson.toJson(actRules);
            ActRules snapshotRules = snapshotActRules(actRules);
            return new CacheResult(oldImagePath, json, snapshotRules);
        }
    }

    /**
     * 从缓存中删除指定规则。
     * @return 删除结果（含 imagePath）；未找到返回 null
     */
    DeleteResult deleteRule(String packageName, RuleRecord viewRule) {
        synchronized (mAppRulesCache) {
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules == null) return null;
            List<RuleRecord> viewRules = actRules.get(viewRule.activityClass);
            if (viewRules == null) return null;
            int idx = findRuleIndex(viewRules, viewRule);
            boolean removed = idx >= 0 ? viewRules.remove(idx) != null : false;
            if (!removed) return null;
            if (viewRules.isEmpty()) {
                actRules.remove(viewRule.activityClass);
            }
            String json = mGson.toJson(actRules);
            ActRules snapshotRules = snapshotActRules(actRules);
            if (actRules.isEmpty()) {
                mAppRulesCache.remove(packageName);
            }
            return new DeleteResult(json, snapshotRules, viewRule.imagePath);
        }
    }

    /** 删除某应用的所有规则 */
    boolean deleteRules(String packageName) {
        synchronized (mAppRulesCache) {
            if (mAppRulesCache.containsKey(packageName)) {
                mAppRulesCache.remove(packageName);
                return true;
            }
            return false;
        }
    }

    /**
     * 收集某应用中所有被引用的图片路径（供孤儿清理使用）。
     * @param imageCollector 用于收集引用路径的回调
     * @param <T> 包目录类型（File）
     */
    <T> void collectReferencedImages(T packageDir, String packageName,
            java.util.function.BiConsumer<T, java.util.Set<String>> collector) {
        synchronized (mAppRulesCache) {
            java.util.Set<String> referenced = new java.util.HashSet<>();
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules != null) {
                for (List<RuleRecord> rules : actRules.values()) {
                    for (RuleRecord rule : rules) {
                        if (!android.text.TextUtils.isEmpty(rule.imagePath))
                            referenced.add(rule.imagePath);
                        if (!android.text.TextUtils.isEmpty(rule.modImagePath))
                            referenced.add(rule.modImagePath);
                    }
                }
            }
            collector.accept(packageDir, referenced);
        }
    }

    // ---- 工具方法 ----

    /** 创建 ActRules 的快照（用于 IPC 传输）*/
    ActRules snapshotActRules(ActRules source) {
        if (source == null) return new ActRules();
        ActRules copy = new ActRules();
        for (Map.Entry<String, List<RuleRecord>> entry : source.entrySet()) {
            if (entry.getValue() == null) continue;
            List<RuleRecord> clonedList = new ArrayList<>(entry.getValue().size());
            for (RuleRecord r : entry.getValue()) {
                if (r != null) clonedList.add(r.clone());
            }
            copy.put(entry.getKey(), clonedList);
        }
        return copy;
    }

    // ---- 内部 DTO ----

    static final class CacheResult {
        final String oldImagePath;
        final String json;
        final ActRules snapshotRules;

        CacheResult(String oldImagePath, String json, ActRules snapshotRules) {
            this.oldImagePath = oldImagePath;
            this.json = json;
            this.snapshotRules = snapshotRules;
        }
    }

    static final class DeleteResult {
        final String json;
        final ActRules snapshotRules;
        final String imagePath;

        DeleteResult(String json, ActRules snapshotRules, String imagePath) {
            this.json = json;
            this.snapshotRules = snapshotRules;
            this.imagePath = imagePath;
        }
    }
}
