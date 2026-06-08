package com.kaisar.xposed.godmode.service;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 瑙勫垯鍐呭瓨缂撳瓨绠＄悊鍣?鈥?绾唴瀛樻搷浣滐紝synchronized 淇濇姢銆?
 * 浠?GodModeManagerService 鎻愬彇鐨勭嫭绔嬭亴璐ｃ€?
 */
final class RuleCacheManager {

    private final AppRules mAppRulesCache = new AppRules();
    private final Gson mGson;
    private final Logger mLogger;

    RuleCacheManager(Gson gson, Logger logger) {
        this.mGson = gson;
        this.mLogger = logger;
    }

    // ---- 缂撳瓨璁块棶 ----

    /** 鑾峰彇鎵€鏈夌紦瀛樼殑瑙勫垯锛堢嚎绋嬪畨鍏ㄥ壇鏈級 */
    AppRules getAllRules() {
        synchronized (mAppRulesCache) {
            AppRules copy = new AppRules();
            copy.putAll(mAppRulesCache);
            return copy;
        }
    }

    /** 鑾峰彇鎸囧畾鍖呯殑瑙勫垯锛屼笉瀛樺湪鍒欒繑鍥炵┖ ActRules */
    ActRules getRules(String packageName) {
        synchronized (mAppRulesCache) {
            return mAppRulesCache.containsKey(packageName)
                    ? mAppRulesCache.get(packageName) : new ActRules();
        }
    }

    /** 灏嗘墍鏈夎鍒欏啓鍏ョ紦瀛橈紙鐢ㄤ簬鍔犺浇鏃舵壒閲忓啓鍏ワ級 */
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

    // ---- 瑙勫垯 CRUD锛堝唴瀛樼紦瀛橀儴鍒嗭級 ----

    /**
     * 灏嗚鍒欏啓鍏ュ唴瀛樼紦瀛樺苟杩斿洖搴忓垪鍖栫粨鏋溿€?
     * 渚?writeRule / updateRule 澶嶇敤銆?
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
     * 浠庣紦瀛樹腑鍒犻櫎鍗曟潯瑙勫垯銆?
     * @return 琚垹闄よ鍒欑殑 imagePath锛屽鏈壘鍒板垯杩斿洖 null
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

    /** 浠庣紦瀛樹腑鍒犻櫎鎸囧畾鍖呯殑鎵€鏈夎鍒?*/
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
     * 鏇存柊缂撳瓨涓寚瀹?app 瑙勫垯闆嗗悎鐨?imagePath 瀛楁锛岃繑鍥炲簭鍒楀寲缁撴灉銆?
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
     * 娓呯悊瀛ょ珛鐨勫浘鐗囨枃浠?鈥?鎵弿鎵€鏈夊寘鐩綍锛屽垹闄ゆ湭琚鍒欏紩鐢ㄧ殑 .webp 鏂囦欢銆?
     * @param imageCollector 鏀堕泦琚紩鐢ㄥ浘鐗囪矾寰勭殑鍥炶皟
     * @param <T> 鐩綍绫诲瀷 (File)
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

    // ---- 鍐呴儴宸ュ叿 ----

    /** 娴呮嫹璐?ActRules锛岀敤浜?IPC 鎺ㄩ€佺殑涓嶅彲鍙樺揩鐓?*/
    ActRules snapshotActRules(ActRules source) {
        if (source == null) return new ActRules();
        ActRules copy = new ActRules();
        for (Map.Entry<String, List<RuleRecord>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    // ---- 缁撴灉 DTO ----

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
