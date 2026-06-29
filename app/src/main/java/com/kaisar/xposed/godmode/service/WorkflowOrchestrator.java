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

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 工作流编排器 — 通过 Handler 异步处理规则持久化和观察者通知。
 * <p>
 * 由 RuleServiceServer 使用。
 * 通过 HandlerThread + Handler 委托给 RulePersistManager 和 ObserverRegistry。
 */
final class WorkflowOrchestrator implements Handler.Callback {

    // ===== 消息码 =====
    static final int LOAD_RULES = 0x00001;
    static final int WRITE_RULE = 0x00002;
    static final int DELETE_RULE = 0x00004;
    static final int DELETE_RULES = 0x00008;
    static final int UPDATE_RULE = 0x000010;
    static final int CLEAN_OBSERVERS = 0x000020;
    static final int CLEAN_ORPHANS = 0x000040;

    private static final long ORPHAN_CLEAN_INTERVAL = 120_000L;

    // ===== 核心 Manager =====
    private final RuleCacheManager mCacheManager;
    private final RulePersistManager mPersistManager;
    private final ObserverRegistry mObserverManager;

    // ===== 实用工具 =====
    private final Logger mLogger;
    private final Handler mHandle;
    private final Consumer<String> mToolbarItemsCallback;

    // ===== 运行时状态 =====
    private volatile boolean mDataLoaded;
    private volatile boolean mOrphanCleanPending;
    private final HandlerThread mWorkThread;

    WorkflowOrchestrator(Gson gson, Logger logger, RuleCacheManager cacheManager,
                         Consumer<String> toolbarItemsCallback) {
        this.mLogger = logger;
        this.mCacheManager = cacheManager;
        this.mToolbarItemsCallback = toolbarItemsCallback;

        mWorkThread = new HandlerThread("work-thread");
        mWorkThread.start();
        mHandle = new Handler(mWorkThread.getLooper(), this);

        mPersistManager = new RulePersistManager(gson, Logger.getLogger("RulePersistManager"), mHandle, mCacheManager);
        mObserverManager = new ObserverRegistry(Logger.getLogger("ObserverRegistry"), mHandle, CLEAN_OBSERVERS);

        mHandle.sendEmptyMessage(LOAD_RULES);
    }

    // ===== 消息码枚举 — 类型安全的操作标识 =====

    enum MessageCode {
        LOAD_RULES,
        WRITE_RULE,
        DELETE_RULE,
        DELETE_RULES,
        UPDATE_RULE,
        CLEAN_OBSERVERS,
        CLEAN_ORPHANS
    }

    // ===== 泛型消息体（通过 msg.obj 传递）=====

    /** 统一规则操作消息体 — 替代 WriteRuleMsg/DeleteRuleMsg/UpdateRuleMsg */
    static final class RuleMessage {
        final MessageCode code;
        final String packageName;
        @androidx.annotation.Nullable final RuleRecord viewRule;
        @androidx.annotation.Nullable final Bitmap snapshot;
        @androidx.annotation.Nullable final String json;
        @androidx.annotation.Nullable final ActRules snapshotRules;
        @androidx.annotation.Nullable final String imagePath;

        private RuleMessage(MessageCode code, String packageName, RuleRecord viewRule,
                Bitmap snapshot, String json, ActRules snapshotRules, String imagePath) {
            this.code = code;
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = snapshot;
            this.json = json;
            this.snapshotRules = snapshotRules;
            this.imagePath = imagePath;
        }

        static RuleMessage forWrite(String packageName, RuleRecord viewRule, Bitmap snapshot) {
            return new RuleMessage(MessageCode.WRITE_RULE, packageName, viewRule,
                    snapshot, null, null, null);
        }

        static RuleMessage forWriteWithJson(String packageName, RuleRecord viewRule,
                String json, ActRules snapshotRules) {
            return new RuleMessage(MessageCode.WRITE_RULE, packageName, viewRule,
                    null, json, snapshotRules, null);
        }

        static RuleMessage forDelete(String packageName, String json,
                ActRules snapshotRules, String imagePath) {
            return new RuleMessage(MessageCode.DELETE_RULE, packageName, null,
                    null, json, snapshotRules, imagePath);
        }

        static RuleMessage forUpdate(String packageName, String json, ActRules snapshotRules) {
            return new RuleMessage(MessageCode.UPDATE_RULE, packageName, null,
                    null, json, snapshotRules, null);
        }
    }

    // ===== 数据加载状态 =====

    /** 数据是否已加载完成 */
    boolean isDataLoaded() {
        return mDataLoaded;
    }

    // ===================================================================
    // Handler 消息分发 — 委托给各 Manager 处理
    // ===================================================================

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case WRITE_RULE:
                handleWriteRule(msg);
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
    // 异步 AIDL 实现 — 由 RuleServiceServer AIDL 调用
    // ===================================================================

    /**
     * 异步写入规则。
     * <p>
     * 两路分支：
     * <ul>
     *   <li><b>带快照</b>：不预先更新缓存，handler 中先执行 I/O（saveBitmap），成功后再更新缓存 + 持久化 + 通知。
     *       避免 saveBitmap 失败导致缓存已更新但磁盘无数据的断裂。</li>
     *   <li><b>纯 JSON</b>：无 I/O 风险，同步更新缓存后 handler 只做持久化 + 通知。</li>
     * </ul>
     */
    boolean writeRuleAsync(String packageName, RuleRecord viewRule, Bitmap snapshot) {
        try {
            Object writeMsg;
            if (snapshot != null) {
                writeMsg = RuleMessage.forWrite(packageName, viewRule, snapshot);
            } else {
                RuleCacheManager.CacheResult cr =
                        mCacheManager.applyRuleToCache(packageName, viewRule, true);
                if (cr.oldImagePath != null) {
                    try {
                        FileUtils.delete(cr.oldImagePath);
                    } catch (Exception e) {
                        mLogger.w("write rule (json path): delete old image failed", e);
                    }
                }
                writeMsg = RuleMessage.forWriteWithJson(packageName, viewRule, cr.json, cr.snapshotRules);
            }
            mHandle.obtainMessage(WRITE_RULE, writeMsg).sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("write rule failed", e);
            return false;
        }
    }

    /** 异步更新规则 — 先应用缓存，再发送 Handler 消息持久化 + 通知观察者 */
    boolean updateRuleAsync(String packageName, RuleRecord viewRule) {
        try {
            RuleCacheManager.CacheResult cr =
                    mCacheManager.applyRuleToCache(packageName, viewRule, false);
            mHandle.obtainMessage(UPDATE_RULE,
                    RuleMessage.forUpdate(packageName, cr.json, cr.snapshotRules)).sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("update rule failed", e);
            return false;
        }
    }

    /** 异步删除规则 — 从缓存移除并持久化 */
    boolean deleteRuleAsync(String packageName, RuleRecord viewRule) {
        try {
            RuleCacheManager.DeleteResult dr = mCacheManager.deleteRule(packageName, viewRule);
            if (dr == null) return false;
            mHandle.obtainMessage(DELETE_RULE,
                    RuleMessage.forDelete(packageName, dr.json, dr.snapshotRules, dr.imagePath))
                    .sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("delete rule failed", e);
            return false;
        }
    }

    /** 异步删除某应用所有规则 */
    boolean deleteRulesAsync(String packageName) {
        mLogger.d("delete rules pkg=" + packageName + " size=" + mCacheManager.size());
        if (mCacheManager.deleteRules(packageName)) {
            mHandle.obtainMessage(DELETE_RULES, packageName).sendToTarget();
            return true;
        }
        return false;
    }

    // ===================================================================
    // 观察者管理
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
    // RulePersistManager 委托 — 供 AIDL 调用
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

    /** 关闭工作线程，释放资源。调用后不应再使用此实例。 */
    void shutdown() {
        mHandle.removeCallbacksAndMessages(null);
        mWorkThread.quitSafely();
    }

    // ===================================================================
    // Handler 消息处理
    // ===================================================================

    /**
     * 处理 {@link #WRITE_RULE} 消息。
     * <p>
     * <b>快照分支</b>：先执行 I/O（删除旧图、保存新图），成功后更新缓存 + 持久化 + 通知观察者。
     * 如果 saveBitmap 失败，缓存未被修改，无需回滚。消除旧的 UPDATE_IMAGE_PATH 异步链断裂风险。
     * <br>
     * <b>JSON 分支</b>：缓存已在 writeRuleAsync 中更新，handler 只负责持久化 + 通知。
     */
    private void handleWriteRule(Message msg) {
        RuleMessage m = (RuleMessage) msg.obj;
        if (m.snapshot != null) {
            // ── 快照分支：先 I/O 保存新图，成功后再更新缓存 + 持久化，最后清理旧图 ──

            // 1) 查询缓存中旧 imagePath（只读），待新图保存并持久化成功后再清理
            String oldImagePath = mCacheManager.getOldImagePath(m.packageName, m.viewRule);

            // 2) I/O 边界：保存新截图。失败则直接返回，缓存未修改，旧图仍有效
            String newImagePath;
            try {
                newImagePath = mPersistManager.saveBitmap(m.snapshot,
                        mPersistManager.getAppDataDir(m.packageName));
            } catch (IOException e) {
                mLogger.w("write rule: save bitmap failed — cache untouched", e);
                return;
            }
            if (newImagePath == null) {
                mLogger.w("write rule aborted: save snapshot returned null — cache untouched",
                        (String) null);
                return;
            }

            // 3) I/O 成功：更新缓存中规则的 imagePath，然后持久化 + 通知观察者
            try {
                m.viewRule.imagePath = newImagePath;
                RuleCacheManager.CacheResult cr = mCacheManager.applyRuleToCache(
                        m.packageName, m.viewRule, false);
                mObserverManager.notifyObserverRuleChanged(m.packageName, cr.snapshotRules);
                mPersistManager.safePersistRules(m.packageName, cr.json);
                // 4) 持久化成功后安全删除旧图，此时即使删除失败也不影响规则完整性
                if (!android.text.TextUtils.isEmpty(oldImagePath)) {
                    try {
                        FileUtils.delete(oldImagePath);
                    } catch (Exception e) {
                        mLogger.w("write rule: delete old image failed", e);
                    }
                }
                scheduleOrphanCleanup();
                mLogger.d("write rule: snapshot persist complete for " + m.packageName);
            } catch (Exception e) {
                mLogger.w("write rule: persist after snapshot failed", e);
            }
        } else {
            // ── JSON 分支：缓存已在 writeRuleAsync 中更新，只持久化 + 通知 ──
            try {
                mObserverManager.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
                mPersistManager.safePersistRules(m.packageName, m.json);
                scheduleOrphanCleanup();
                mLogger.d("write rule: json persist complete for " + m.packageName);
            } catch (Exception e) {
                mLogger.w("write rule: persist failed", e);
            }
        }
    }

    private void handleDeleteRule(Message msg) {
        try {
            RuleMessage m = (RuleMessage) msg.obj;
            // 先持久化规则，再清理图片，确保持久化不因图片删除失败被跳过
            mObserverManager.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
            mPersistManager.safePersistRules(m.packageName, m.json);
            if (!android.text.TextUtils.isEmpty(m.imagePath)) {
                try {
                    FileUtils.delete(m.imagePath);
                } catch (Exception e) {
                    mLogger.w("delete rule: delete image failed", e);
                }
            }
            scheduleOrphanCleanup();
            mLogger.d("delete rule: complete for " + m.packageName);
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
            RuleMessage m = (RuleMessage) msg.obj;
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
        } catch (IOException e) {
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
