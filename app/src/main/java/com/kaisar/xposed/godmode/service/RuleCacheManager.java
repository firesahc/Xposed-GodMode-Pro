package com.kaisar.xposed.godmode.service;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.ViewRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则内存缓存管理器 — 纯内存操作，synchronized 保护。
 * 从 GodModeManagerService 提取的独立职责。
 */
final class RuleCacheManager {

    private final AppRules mAppRulesCache = new AppRules();
    private final Gson mGson;
    private final Logger mLogger;

    RuleCacheManager(Gson gson, Logger logger) {
        this.mGson = gson;
        this.mLogger = logger;
    }

    // ---- 缓存访问 ----

    /** 获取所有缓存的规则（线程安全副本） */
    AppRules getAllRules() {
        synchronized (mAppRulesCache) {
            AppRules copy = new AppRules();
            copy.putAll(mAppRulesCache);
            return copy;
        }
    }

    /** 获取指定包的规则，不存在则返回空 ActRules */
    ActRules getRules(String packageName) {
        synchronized (mAppRulesCache) {
            return mAppRulesCache.containsKey(packageName)
                    ? mAppRulesCache.get(packageName) : new ActRules();
        }
    }

    /** 将所有规则写入缓存（用于加载时批量写入） */
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

    // ---- 规则 CRUD（内存缓存部分） ----

    /**
     * 将规则写入内存缓存并返回序列化结果。
     * 供 writeRule / updateRule 复用。
     */
    CacheResult applyRuleToCache(String packageName, ViewRule viewRule, boolean captureOldImagePath) {
        synchronized (mAppRulesCache) {
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules == null) {
                mAppRulesCache.put(packageName, actRules = new ActRules());
            }
            List<ViewRule> viewRules = actRules.computeIfAbsent(viewRule.activityClass, k -> new ArrayList<>());
            int index = viewRules.indexOf(viewRule);
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
     * 从缓存中删除单条规则。
     * @return 被删除规则的 imagePath，如未找到则返回 null
     */
    DeleteResult deleteRule(String packageName, ViewRule viewRule) {
        synchronized (mAppRulesCache) {
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules == null) return null;
            List<ViewRule> viewRules = actRules.get(viewRule.activityClass);
            if (viewRules == null) return null;
            boolean removed = viewRules.remove(viewRule);
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

    /** 从缓存中删除指定包的所有规则 */
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
     * 更新缓存中指定 app 规则集合的 imagePath 字段，返回序列化结果。
     */
    CacheResult updateImagePath(String packageName, ViewRule viewRule, String newImagePath) {
        synchronized (mAppRulesCache) {
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules != null) {
                List<ViewRule> rules = actRules.get(viewRule.activityClass);
                if (rules != null) {
                    int idx = rules.indexOf(viewRule);
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
     * 清理孤立的图片文件 — 扫描所有包目录，删除未被规则引用的 .webp 文件。
     * @param imageCollector 收集被引用图片路径的回调
     * @param <T> 目录类型 (File)
     */
    <T> void collectReferencedImages(T packageDir, String packageName,
            java.util.function.BiConsumer<T, java.util.Set<String>> collector) {
        synchronized (mAppRulesCache) {
            java.util.Set<String> referenced = new java.util.HashSet<>();
            ActRules actRules = mAppRulesCache.get(packageName);
            if (actRules != null) {
                for (List<ViewRule> rules : actRules.values()) {
                    for (ViewRule rule : rules) {
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

    // ---- 内部工具 ----

    /** 浅拷贝 ActRules，用于 IPC 推送的不可变快照 */
    ActRules snapshotActRules(ActRules source) {
        if (source == null) return new ActRules();
        ActRules copy = new ActRules();
        for (Map.Entry<String, List<ViewRule>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    // ---- 结果 DTO ----

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
