package com.kaisar.xposed.godmode.service;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.util.function.Consumer;

/**
 * 鐟欏嫬鍨銉ょ稊濞翠胶绱幒鎺戞珤 閳?缁狅紕鎮?Handler 濞戝牊浼呴崚鍡楀絺閵嗕浇顫夐崚娆愬瘮娑斿懎瀵查崣濠咁潎鐎电喕鈧懘鈧氨鐓￠惃鍕紣娴ｆ粍绁﹂妴?
 * <p>
 * 娴?GodModeManagerService 閹绘劕褰囬惃鍕椽閹烘帟浜寸拹锝冣偓?
 * 閸愬懘鍎撮幐浣规箒 HandlerThread閵嗕笭andler閵嗕阜ulePersistManager 閸?ObserverManager閵?
 */
final class WorkflowOrchestrator implements Handler.Callback {

    // ===== POJO 濞戝牊浼呯猾浼欑礄閺囧じ鍞?Object[] 娴肩姴寮敍?=====

    /** WRITE_RULE 濞戝牊浼呮潪鍊熷祹 */
    static final class WriteRuleMsg {
        final String packageName;
        final RuleRecord viewRule;
        @androidx.annotation.Nullable final Bitmap snapshot;
        @androidx.annotation.Nullable final String oldImagePath;
        @androidx.annotation.Nullable final String json;
        @androidx.annotation.Nullable final ActRules snapshotRules;

        /** 鐢缚缍呴崶鐐€柅?*/
        WriteRuleMsg(String packageName, RuleRecord viewRule, Bitmap snapshot, String oldImagePath) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = snapshot;
            this.oldImagePath = oldImagePath;
            this.json = null;
            this.snapshotRules = null;
        }

        /** 閺冪姳缍呴崶鐐€柅鐙呯礄閻╁瓨甯?JSON閿?*/
        WriteRuleMsg(String packageName, RuleRecord viewRule, String json, ActRules snapshotRules) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = null;
            this.oldImagePath = null;
            this.json = json;
            this.snapshotRules = snapshotRules;
        }
    }

    /** UPDATE_IMAGE_PATH 濞戝牊浼呮潪鍊熷祹 */
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

    /** DELETE_RULE 濞戝牊浼呮潪鍊熷祹 */
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

    /** UPDATE_RULE 濞戝牊浼呮潪鍊熷祹 */
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

    // ===== 濞戝牊浼呮禒锝囩垳 =====
    static final int LOAD_RULES = 0x00001;
    static final int WRITE_RULE = 0x00002;
    static final int DELETE_RULE = 0x00004;
    static final int DELETE_RULES = 0x00008;
    static final int UPDATE_RULE = 0x000010;
    static final int CLEAN_OBSERVERS = 0x000020;
    static final int CLEAN_ORPHANS = 0x000040;
    static final int UPDATE_IMAGE_PATH = 0x000080;

    private static final long ORPHAN_CLEAN_INTERVAL = 120_000L;

    // ===== 缂佸嫬鎮庨惃?Manager =====
    private final RuleCacheManager mCacheManager;
    private final RulePersistManager mPersistManager;
    private final ObserverManager mObserverManager;

    // ===== 閸╄櫣顢呯拋鐐煢 =====
    private final Logger mLogger;
    private final Handler mHandle;
    private final Consumer<String> mToolbarItemsCallback;

    // ===== 閻樿埖鈧礁鐡у▓?=====
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

    // ===== 閸忣剙绱戠拋鍧楁６閸?=====

    /** 鐟欏嫬鍨弫鐗堝祦閺勵垰鎯佸韫矤绾句胶娲忛崝鐘烘祰鐎瑰本鍨?*/
    boolean isDataLoaded() {
        return mDataLoaded;
    }

    // ===================================================================
    // Handler 濞戝牊浼呯紓鏍ㄥ笓 閳?閸楀繗鐨熼崥?Manager 娑斿妫块惃鍕紣娴ｆ粍绁?
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
    // 瀵倹顒?AIDL 婵梹澧弬瑙勭《閿涘牏鏁?GodModeManagerService AIDL 閺傝纭剁拫鍐暏閿?
    // ===================================================================

    /** 缂傛牗甯撻崘娆忓弳鐟欏嫬鍨銉ょ稊濞翠緤绱扮紓鎾崇摠 閳?濞戝牊浼呴梼鐔峰灙 閳?閹镐椒绠欓崠?+ 鐟欏倸鐧傞懓鍛粹偓姘辩叀 */
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

    /** 缂傛牗甯撻弴瀛樻煀鐟欏嫬鍨銉ょ稊濞翠緤绱扮紓鎾崇摠 閳?濞戝牊浼呴梼鐔峰灙 閳?閹镐椒绠欓崠?+ 鐟欏倸鐧傞懓鍛粹偓姘辩叀 */
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

    /** 缂傛牗甯撻崚鐘绘珟閸楁洘娼憴鍕灟瀹搞儰缍斿ù?*/
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

    /** 缂傛牗甯撻崚鐘绘珟閸栧懎鍙忛柈銊潐閸掓瑥浼愭担婊勭ウ */
    boolean deleteRulesAsync(String packageName) {
        mLogger.d("delete rules pkg=" + packageName + " size=" + mCacheManager.size());
        if (mCacheManager.deleteRules(packageName)) {
            mHandle.obtainMessage(DELETE_RULES, packageName).sendToTarget();
            return true;
        }
        return false;
    }

    // ===================================================================
    // 鐟欏倸鐧傞懓鍛吀閻炲棗顫欓幍?
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
    // RulePersistManager 婵梹澧敍鍫滅返 AIDL 閺傝纭堕惄瀛樺复鐠嬪啰鏁ら敍?
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
    // Handler 婢跺嫮鎮婇崳銊︽煙濞?
    // ===================================================================

    private void handleWriteRule(Message msg) {
        WriteRuleMsg m = (WriteRuleMsg) msg.obj;
        if (m.snapshot != null) {
            // 鐢缚缍呴崶鎾呯窗閸掔娀娅庨弮褍娴?閳?娣囨繂鐡ㄩ弬鏉挎禈 閳?閺囧瓨鏌?imagePath
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
            // 閺冪姳缍呴崶鎾呯窗閻╁瓨甯撮幐浣风畽閸栨牞顫夐崚?JSON 楠炲爼鈧氨鐓＄憴鍌氱檪閼?
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
