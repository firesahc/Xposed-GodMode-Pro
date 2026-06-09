package com.kaisar.xposed.godmode.service;

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
 * 由 GodModeManagerService 使用。
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
            copy.putAll(mAppRulesCache);
            return copy;
        }
    }

    /** 返回指定包的规则副本（不存在则返回空 ActRules） */
    ActRules getRules(String packageName) {
        synchronized (mAppRulesCache) {
            return mAppRulesCache.containsKey(packageName)
                    ? mAppRulesCache.get(packageName) : new ActRules();
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
            int index = -1;
            for (int i = 0; i < viewRules.size(); i++) {
                if (viewRules.get(i).isSameViewAs(viewRule)) {
                    index = i;
                    break;
                }
            }
            String oldImagePath = null;
            if (index >= 0) {
                if (captureOldImagePath) {
                    oldImagePath = viewRules.get(index).imagePath;
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
            int idx = -1;
            for (int i = 0; i < viewRules.size(); i++) {
                if (viewRules.get(i).isSameViewAs(viewRule)) {
                    idx = i;
                    break;
                }
            }
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
     * 更新缓存中某条规则的 imagePath（快照路径变更）
     */
    CacheResult updateImagePath(String packageName, RuleRecord viewRule, String newImagePath) {
        synchronized (mAppRulesCache) {
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules != null) {
                List<RuleRecord> rules = actRules.get(viewRule.activityClass);
                if (rules != null) {
                    int idx = -1;
                    for (int i = 0; i < rules.size(); i++) {
                        if (rules.get(i).isSameViewAs(viewRule)) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx >= 0) {
                        rules.get(idx).imagePath = newImagePath;
                    }
                }
            }
            String json = mGson.toJson(actRules);
            ActRules snapshotRules = snapshotActRules(actRules);
            return new CacheResult(null, json, snapshotRules);
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
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
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
