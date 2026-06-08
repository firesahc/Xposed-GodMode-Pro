package com.kaisar.xposed.godmode.service;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.function.Consumer;

/**
 * 瑙勫垯宸ヤ綔娴佺紪鎺掑櫒 鈥?绠＄悊 Handler 娑堟伅鍒嗗彂銆佽鍒欐寔涔呭寲鍙婅瀵熻€呴€氱煡鐨勫伐浣滄祦銆?
 * <p>
 * 浠?GodModeManagerService 鎻愬彇鐨勭紪鎺掕亴璐ｃ€?
 * 鍐呴儴鎸佹湁 HandlerThread銆丠andler銆丷ulePersistManager 鍜?ObserverManager銆?
 */
final class WorkflowOrchestrator implements Handler.Callback {

    // ===== POJO 娑堟伅绫伙紙鏇夸唬 Object[] 浼犲弬锛?=====

    /** WRITE_RULE 娑堟伅杞借嵎 */
    static final class WriteRuleMsg {
        final String packageName;
        final RuleRecord viewRule;
        @androidx.annotation.Nullable final Bitmap snapshot;
        @androidx.annotation.Nullable final String oldImagePath;
        @androidx.annotation.Nullable final String json;
        @androidx.annotation.Nullable final ActRules snapshotRules;

        /** 甯︿綅鍥炬瀯閫?*/
        WriteRuleMsg(String packageName, RuleRecord viewRule, Bitmap snapshot, String oldImagePath) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = snapshot;
            this.oldImagePath = oldImagePath;
            this.json = null;
            this.snapshotRules = null;
        }

        /** 鏃犱綅鍥炬瀯閫狅紙鐩存帴 JSON锛?*/
        WriteRuleMsg(String packageName, RuleRecord viewRule, String json, ActRules snapshotRules) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = null;
            this.oldImagePath = null;
            this.json = json;
            this.snapshotRules = snapshotRules;
        }
    }

    /** UPDATE_IMAGE_PATH 娑堟伅杞借嵎 */
    static final class UpdateImagePathMsg {
        final String packageName;
        final RuleRecord viewRule;
        final String newImagePath;

        UpdateImagePathMsg(String packageName, RuleRecord viewRule, String newImagePath) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.newImagePath = newImagePath;
        }
    }

    /** DELETE_RULE 娑堟伅杞借嵎 */
    static final class DeleteRuleMsg {
        final String packageName;
        final String json;
        final ActRules snapshotRules;
        final String imagePath;

        DeleteRuleMsg(String packageName, String json, ActRules snapshotRules, String imagePath) {
            this.packageName = packageName;
            this.json = json;
            this.snapshotRules = snapshotRules;
            this.imagePath = imagePath;
        }
    }

    /** UPDATE_RULE 娑堟伅杞借嵎 */
    static final class UpdateRuleMsg {
        final String packageName;
        final String json;
        final ActRules snapshotRules;

        UpdateRuleMsg(String packageName, String json, ActRules snapshotRules) {
            this.packageName = packageName;
            this.json = json;
            this.snapshotRules = snapshotRules;
        }
    }

    // ===== 娑堟伅浠ｇ爜 =====
    static final int LOAD_RULES = 0x00001;
    static final int WRITE_RULE = 0x00002;
    static final int DELETE_RULE = 0x00004;
    static final int DELETE_RULES = 0x00008;
    static final int UPDATE_RULE = 0x000010;
    static final int CLEAN_OBSERVERS = 0x000020;
    static final int CLEAN_ORPHANS = 0x000040;
    static final int UPDATE_IMAGE_PATH = 0x000080;

    private static final long ORPHAN_CLEAN_INTERVAL = 120_000L;

    // ===== 缁勫悎鐨?Manager =====
    private final RuleCacheManager mCacheManager;
    private final RulePersistManager mPersistManager;
    private final ObserverManager mObserverManager;

    // ===== 鍩虹璁炬柦 =====
    private final Logger mLogger;
    private final Handler mHandle;
    private final Consumer<String> mToolbarItemsCallback;

    // ===== 鐘舵€佸瓧娈?=====
    private volatile boolean mDataLoaded;
    private volatile boolean mOrphanCleanPending;

    WorkflowOrchestrator(Gson gson, Logger logger, RuleCacheManager cacheManager,
                         Consumer<String> toolbarItemsCallback) {
        this.mLogger = logger;
        this.mCacheManager = cacheManager;
        this.mToolbarItemsCallback = toolbarItemsCallback;

        HandlerThread workThread = new HandlerThread("work-thread");
        workThread.start();
        mHandle = new Handler(workThread.getLooper(), this);

        mPersistManager = new RulePersistManager(gson, mLogger, mHandle, mCacheManager);
        mObserverManager = new ObserverManager(mLogger, mHandle, CLEAN_OBSERVERS);

        mHandle.sendEmptyMessage(LOAD_RULES);
    }

    // ===== 鍏紑璁块棶鍣?=====

    /** 瑙勫垯鏁版嵁鏄惁宸蹭粠纾佺洏鍔犺浇瀹屾垚 */
    boolean isDataLoaded() {
        return mDataLoaded;
    }

    // ===================================================================
    // Handler 娑堟伅缂栨帓 鈥?鍗忚皟鍚?Manager 涔嬮棿鐨勫伐浣滄祦
    // ===================================================================

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case WRITE_RULE:
                handleWriteRule(msg);
                break;
            case UPDATE_IMAGE_PATH:
                handleUpdateImagePath(msg);
                break;
            case DELETE_RULE:
                handleDeleteRule(msg);
                break;
            case DELETE_RULES:
                handleDeleteRules(msg);
                break;
            case UPDATE_RULE:
                handleUpdateRule(msg);
                break;
            case LOAD_RULES:
                handleLoadRules();
                break;
            case CLEAN_OBSERVERS:
                handleCleanObservers();
                break;
            case CLEAN_ORPHANS:
                handleCleanOrphans();
                break;
            case RulePersistManager.MSG_DEBOUNCE_WRITE:
                mPersistManager.handleDebouncedWrite((String) msg.obj);
                break;
        }
        return true;
    }

    // ===================================================================
    // 寮傛 AIDL 濮旀墭鏂规硶锛堢敱 GodModeManagerService AIDL 鏂规硶璋冪敤锛?
    // ===================================================================

    /** 缂栨帓鍐欏叆瑙勫垯宸ヤ綔娴侊細缂撳瓨 鈫?娑堟伅闃熷垪 鈫?鎸佷箙鍖?+ 瑙傚療鑰呴€氱煡 */
    boolean writeRuleAsync(String packageName, RuleRecord viewRule, Bitmap snapshot) {
        try {
            RuleCacheManager.CacheResult cr =
                    mCacheManager.applyRuleToCache(packageName, viewRule, true);
            Object writeMsg = snapshot != null
                    ? new WriteRuleMsg(packageName, viewRule, snapshot, cr.oldImagePath)
                    : new WriteRuleMsg(packageName, viewRule, cr.json, cr.snapshotRules);
            mHandle.obtainMessage(WRITE_RULE, writeMsg).sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("write rule failed", e);
            return false;
        }
    }

    /** 缂栨帓鏇存柊瑙勫垯宸ヤ綔娴侊細缂撳瓨 鈫?娑堟伅闃熷垪 鈫?鎸佷箙鍖?+ 瑙傚療鑰呴€氱煡 */
    boolean updateRuleAsync(String packageName, RuleRecord viewRule) {
        try {
            RuleCacheManager.CacheResult cr =
                    mCacheManager.applyRuleToCache(packageName, viewRule, false);
            mHandle.obtainMessage(UPDATE_RULE,
                    new UpdateRuleMsg(packageName, cr.json, cr.snapshotRules)).sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("update rule failed", e);
            return false;
        }
    }

    /** 缂栨帓鍒犻櫎鍗曟潯瑙勫垯宸ヤ綔娴?*/
    boolean deleteRuleAsync(String packageName, RuleRecord viewRule) {
        try {
            RuleCacheManager.DeleteResult dr = mCacheManager.deleteRule(packageName, viewRule);
            if (dr == null) return false;
            mHandle.obtainMessage(DELETE_RULE,
                    new DeleteRuleMsg(packageName, dr.json, dr.snapshotRules, dr.imagePath))
                    .sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("delete rule failed", e);
            return false;
        }
    }

    /** 缂栨帓鍒犻櫎鍖呭叏閮ㄨ鍒欏伐浣滄祦 */
    boolean deleteRulesAsync(String packageName) {
        mLogger.d("delete rules pkg=" + packageName + " size=" + mCacheManager.size());
        if (mCacheManager.deleteRules(packageName)) {
            mHandle.obtainMessage(DELETE_RULES, packageName).sendToTarget();
            return true;
        }
        return false;
    }

    // ===================================================================
    // 瑙傚療鑰呯鐞嗗鎵?
    // ===================================================================

    void addObserver(String packageName, IObserver observer, boolean editModeEnabled,
                     ActRules rules) {
        mObserverManager.addObserver(packageName, observer, editModeEnabled, rules);
    }

    void removeObserver(String packageName, IObserver observer) {
        mObserverManager.removeObserver(packageName, observer);
    }

    void notifyEditModeChanged(boolean enable) {
        mObserverManager.notifyObserverEditModeChanged(enable);
    }

    // ===================================================================
    // RulePersistManager 濮旀墭锛堜緵 AIDL 鏂规硶鐩存帴璋冪敤锛?
    // ===================================================================

    String saveBitmap(Bitmap bitmap, String dir) {
        return mPersistManager.saveBitmap(bitmap, dir);
    }

    String getAppDataDir(String packageName) throws java.io.FileNotFoundException {
        return mPersistManager.getAppDataDir(packageName);
    }

    boolean isValidImagePath(String filePath) {
        return mPersistManager.isValidImagePath(filePath);
    }

    void persistToolbarHiddenItems(String items) {
        mPersistManager.persistToolbarHiddenItems(items);
    }

    // ===================================================================
    // Handler 澶勭悊鍣ㄦ柟娉?
    // ===================================================================

    private void handleWriteRule(Message msg) {
        WriteRuleMsg m = (WriteRuleMsg) msg.obj;
        if (m.snapshot != null) {
            // 甯︿綅鍥撅細鍒犻櫎鏃у浘 鈫?淇濆瓨鏂板浘 鈫?鏇存柊 imagePath
            try {
                if (m.oldImagePath != null && !android.text.TextUtils.isEmpty(m.oldImagePath)) {
                    FileUtils.delete(m.oldImagePath);
                }
            } catch (Exception e) {
                mLogger.w("write rule: delete old image failed", e);
            }
            String newImagePath;
            try {
                newImagePath = mPersistManager.saveBitmap(m.snapshot,
                        mPersistManager.getAppDataDir(m.packageName));
            } catch (Exception e) {
                mLogger.w("write rule: save bitmap failed", e);
                return;
            }
            if (newImagePath == null) {
                mLogger.w("write rule aborted: save snapshot returned null", (String) null);
                return;
            }
            mHandle.obtainMessage(UPDATE_IMAGE_PATH,
                    new UpdateImagePathMsg(m.packageName, m.viewRule, newImagePath)).sendToTarget();
        } else {
            // 鏃犱綅鍥撅細鐩存帴鎸佷箙鍖栬鍒?JSON 骞堕€氱煡瑙傚療鑰?
            try {
                mObserverManager.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
                mPersistManager.safePersistRules(m.packageName, m.json);
                scheduleOrphanCleanup();
            } catch (Exception e) {
                mLogger.w("write rule: persist failed", e);
            }
        }
    }

    private void handleUpdateImagePath(Message msg) {
        try {
            UpdateImagePathMsg m = (UpdateImagePathMsg) msg.obj;
            RuleCacheManager.CacheResult cr =
                    mCacheManager.updateImagePath(m.packageName, m.viewRule, m.newImagePath);
            mObserverManager.notifyObserverRuleChanged(m.packageName, cr.snapshotRules);
            mPersistManager.safePersistRules(m.packageName, cr.json);
            scheduleOrphanCleanup();
        } catch (Exception e) {
            mLogger.w("update image path failed", e);
        }
    }

    private void handleDeleteRule(Message msg) {
        try {
            DeleteRuleMsg m = (DeleteRuleMsg) msg.obj;
            FileUtils.delete(m.imagePath);
            mObserverManager.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
            mPersistManager.safePersistRules(m.packageName, m.json);
            scheduleOrphanCleanup();
        } catch (Exception e) {
            mLogger.w("delete rule failed", e);
        }
    }

    private void handleDeleteRules(Message msg) {
        try {
            String packageName = (String) msg.obj;
            FileUtils.delete(mPersistManager.getAppDataDir(packageName));
            mObserverManager.notifyObserverRuleChanged(packageName, new ActRules());
        } catch (Exception e) {
            mLogger.w("delete rules failed", e);
        }
    }

    private void handleUpdateRule(Message msg) {
        try {
            UpdateRuleMsg m = (UpdateRuleMsg) msg.obj;
            mPersistManager.safePersistRules(m.packageName, m.json);
            mObserverManager.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
        } catch (Exception e) {
            mLogger.w("update rule failed", e);
        }
    }

    private void handleLoadRules() {
        try {
            mPersistManager.loadRuleData();
            String items = mPersistManager.loadToolbarHiddenItems();
            mToolbarItemsCallback.accept(items);
            mDataLoaded = true;
            mLogger.i("rule data loaded: " + mCacheManager.size() + " packages");
        } catch (Exception e) {
            mLogger.e("loadRuleData failed", e);
            mDataLoaded = true;
        }
    }

    private void handleCleanObservers() {
        mObserverManager.cleanDeadObservers();
    }

    private void handleCleanOrphans() {
        mOrphanCleanPending = false;
        try {
            mPersistManager.cleanAllOrphanImages();
        } catch (Exception e) {
            mLogger.w("orphan cleanup failed", e);
        }
    }

    private void scheduleOrphanCleanup() {
        if (!mOrphanCleanPending) {
            mOrphanCleanPending = true;
            mHandle.sendEmptyMessageDelayed(CLEAN_ORPHANS, ORPHAN_CLEAN_INTERVAL);
        }
    }
}
