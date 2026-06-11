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
 * 工作流编排器 — 通过 Handler 异步处理规则持久化和观察者通知。
 * <p>
 * 由 RuleServiceServer 使用。
 * 通过 HandlerThread + Handler 委托给 RulePersistManager 和 ObserverRegistry。
 */
final class WorkflowOrchestrator implements Handler.Callback {

    // ===== POJO 消息体（通过 msg.obj 传递）=====

    /** WRITE_RULE 消息体 */
    static final class WriteRuleMsg {
        final String packageName;
        final RuleRecord viewRule;
        /** 快照路径：非 null = 带截图写入（I/O 后更新缓存），null = 纯 JSON 写入（缓存已更新） */
        @androidx.annotation.Nullable final Bitmap snapshot;
        /** 纯 JSON 写入时：缓存已更新后的序列化 JSON（供 handler 持久化） */
        @androidx.annotation.Nullable final String json;
        /** 纯 JSON 写入时：缓存已更新后的快照（供 handler 通知观察者） */
        @androidx.annotation.Nullable final ActRules snapshotRules;

        /** 带快照的构造方法 — cache 在 handleWriteRule 中 saveBitmap 成功后更新 */
        WriteRuleMsg(String packageName, RuleRecord viewRule, Bitmap snapshot) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = snapshot;
            this.json = null;
            this.snapshotRules = null;
        }

        /** 带 JSON 数据的构造方法 — cache 在 writeRuleAsync 中已更新，handler 只做持久化 */
        WriteRuleMsg(String packageName, RuleRecord viewRule, String json, ActRules snapshotRules) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = null;
            this.json = json;
            this.snapshotRules = snapshotRules;
        }
    }

    /** DELETE_RULE 消息体 */
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

    /** UPDATE_RULE 消息体 */
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

    // ===== 消息代码 =====
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

    WorkflowOrchestrator(Gson gson, Logger logger, RuleCacheManager cacheManager,
                         Consumer<String> toolbarItemsCallback) {
        this.mLogger = logger;
        this.mCacheManager = cacheManager;
        this.mToolbarItemsCallback = toolbarItemsCallback;

        HandlerThread workThread = new HandlerThread("work-thread");
        workThread.start();
        mHandle = new Handler(workThread.getLooper(), this);

        mPersistManager = new RulePersistManager(gson, Logger.getLogger("RulePersistManager"), mHandle, mCacheManager);
        mObserverManager = new ObserverRegistry(Logger.getLogger("ObserverRegistry"), mHandle, CLEAN_OBSERVERS);

        mHandle.sendEmptyMessage(LOAD_RULES);
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
                // 快照路径：cache 在 handleWriteRule 中 saveBitmap 成功后更新
                writeMsg = new WriteRuleMsg(packageName, viewRule, snapshot);
            } else {
                // JSON 路径：cache 立即更新（无 I/O 风险）
                RuleCacheManager.CacheResult cr =
                        mCacheManager.applyRuleToCache(packageName, viewRule, true);
                writeMsg = new WriteRuleMsg(packageName, viewRule, cr.json, cr.snapshotRules);
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
                    new UpdateRuleMsg(packageName, cr.json, cr.snapshotRules)).sendToTarget();
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
                    new DeleteRuleMsg(packageName, dr.json, dr.snapshotRules, dr.imagePath))
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
        WriteRuleMsg m = (WriteRuleMsg) msg.obj;
        if (m.snapshot != null) {
            // ── 快照分支：I/O 先于缓存更新 ──

            // 1) 查询缓存中旧 imagePath（只读），删除旧截图文件
            String oldImagePath = mCacheManager.getOldImagePath(m.packageName, m.viewRule);
            if (!android.text.TextUtils.isEmpty(oldImagePath)) {
                try {
                    FileUtils.delete(oldImagePath);
                } catch (Exception e) {
                    mLogger.w("write rule: delete old image failed", e);
                }
            }

            // 2) I/O 边界：保存新截图。失败则直接返回，缓存未修改
            String newImagePath;
            try {
                newImagePath = mPersistManager.saveBitmap(m.snapshot,
                        mPersistManager.getAppDataDir(m.packageName));
            } catch (Exception e) {
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
                scheduleOrphanCleanup();
            } catch (Exception e) {
                mLogger.w("write rule: persist after snapshot failed", e);
            }
        } else {
            // ── JSON 分支：缓存已在 writeRuleAsync 中更新，只持久化 + 通知 ──
            try {
                mObserverManager.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
                mPersistManager.safePersistRules(m.packageName, m.json);
                scheduleOrphanCleanup();
            } catch (Exception e) {
                mLogger.w("write rule: persist failed", e);
            }
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
