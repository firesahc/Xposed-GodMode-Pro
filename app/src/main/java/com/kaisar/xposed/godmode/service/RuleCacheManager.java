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
 * 鐟欏嫬鍨崘鍛摠缂傛挸鐡ㄧ粻锛勬倞閸?閳?缁绢垰鍞寸€涙ɑ鎼锋担婊愮礉synchronized 娣囨繃濮㈤妴?
 * 娴?GodModeManagerService 閹绘劕褰囬惃鍕缁斿浜寸拹锝冣偓?
 */
final class RuleCacheManager {

    private final AppRules mAppRulesCache = new AppRules();
    private final Gson mGson;
    private final Logger mLogger;

    RuleCacheManager(Gson gson, Logger logger) {
        this.mGson = gson;
        this.mLogger = logger;
    }

    // ---- 缂傛挸鐡ㄧ拋鍧楁６ ----

    /** 閼惧嘲褰囬幍鈧張澶岀处鐎涙娈戠憴鍕灟閿涘牏鍤庣粙瀣暔閸忋劌澹囬張顒婄礆 */
    AppRules getAllRules() {
        synchronized (mAppRulesCache) {
            AppRules copy = new AppRules();
            copy.putAll(mAppRulesCache);
            return copy;
        }
    }

    /** 閼惧嘲褰囬幐鍥х暰閸栧懐娈戠憴鍕灟閿涘奔绗夌€涙ê婀崚娆掔箲閸ョ偟鈹?ActRules */
    ActRules getRules(String packageName) {
        synchronized (mAppRulesCache) {
            return mAppRulesCache.containsKey(packageName)
                    ? mAppRulesCache.get(packageName) : new ActRules();
        }
    }

    /** 鐏忓棙澧嶉張澶庮潐閸掓瑥鍟撻崗銉х处鐎涙﹫绱欓悽銊ょ艾閸旂姾娴囬弮鑸靛闁插繐鍟撻崗銉礆 */
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

    // ---- 鐟欏嫬鍨?CRUD閿涘牆鍞寸€涙绱︾€涙﹢鍎撮崚鍡礆 ----

    /**
     * 鐏忓棜顫夐崚娆忓晸閸忋儱鍞寸€涙绱︾€涙ê鑻熸潻鏂挎礀鎼村繐鍨崠鏍波閺嬫嚎鈧?
     * 娓?writeRule / updateRule 婢跺秶鏁ら妴?
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
     * 娴犲海绱︾€涙ü鑵戦崚鐘绘珟閸楁洘娼憴鍕灟閵?
     * @return 鐞氼偄鍨归梽銈堫潐閸掓瑧娈?imagePath閿涘苯顩ч張顏呭閸掓澘鍨潻鏂挎礀 null
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

    /** 娴犲海绱︾€涙ü鑵戦崚鐘绘珟閹稿洤鐣鹃崠鍛畱閹碘偓閺堝顫夐崚?*/
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
     * 閺囧瓨鏌婄紓鎾崇摠娑擃厽瀵氱€?app 鐟欏嫬鍨梿鍡楁値閻?imagePath 鐎涙顔岄敍宀冪箲閸ョ偛绨崚妤€瀵茬紒鎾寸亯閵?
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
     * 濞撳懐鎮婄€涖倗鐝涢惃鍕禈閻楀洦鏋冩禒?閳?閹殿偅寮块幍鈧張澶婂瘶閻╊喖缍嶉敍灞藉灩闂勩倖婀悮顐ヮ潐閸掓瑥绱╅悽銊ф畱 .webp 閺傚洣娆㈤妴?
     * @param imageCollector 閺€鍫曟肠鐞氼偄绱╅悽銊ユ禈閻楀洩鐭惧鍕畱閸ョ偠鐨?
     * @param <T> 閻╊喖缍嶇猾璇茬€?(File)
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

    // ---- 閸愬懘鍎村銉ュ徔 ----

    /** 濞村懏瀚圭拹?ActRules閿涘瞼鏁ゆ禍?IPC 閹恒劑鈧胶娈戞稉宥呭讲閸欐ê鎻╅悡?*/
    ActRules snapshotActRules(ActRules source) {
        if (source == null) return new ActRules();
        ActRules copy = new ActRules();
        for (Map.Entry<String, List<RuleRecord>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    // ---- 缂佹挻鐏?DTO ----

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
