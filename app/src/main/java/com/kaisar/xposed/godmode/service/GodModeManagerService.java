package com.kaisar.xposed.godmode.service;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 上帝模式核心管理服务 — 所有跨进程通讯均通过此服务。
 * <p>
 * 该服务通过 XServiceManager 注入到 SystemServer 进程。
 * 采用组合模式，将规则缓存、持久化、观察者管理、权限验证委托给 4 个专职 Manager。
 * Handler 消息分发作为编排层，协调各 Manager 之间的工作流。
 * <p>
 * Client 端通过 {@link com.kaisar.xposed.godmode.injection.bridge.GodModeManager#getDefault()} 使用接口。
 */
public final class GodModeManagerService extends IGodModeManager.Stub implements Handler.Callback {

    // ===== POJO 消息类（替代 Object[] 传参） =====

    /** WRITE_RULE 消息载荷 */
    static final class WriteRuleMsg {
        final String packageName;
        final RuleRecord viewRule;
        @android.annotation.Nullable final Bitmap snapshot;
        @android.annotation.Nullable final String oldImagePath;
        @android.annotation.Nullable final String json;
        @android.annotation.Nullable final ActRules snapshotRules;

        /** 带位图构造 */
        WriteRuleMsg(String packageName, RuleRecord viewRule, Bitmap snapshot, String oldImagePath) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = snapshot;
            this.oldImagePath = oldImagePath;
            this.json = null;
            this.snapshotRules = null;
        }

        /** 无位图构造（直接 JSON） */
        WriteRuleMsg(String packageName, RuleRecord viewRule, String json, ActRules snapshotRules) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = null;
            this.oldImagePath = null;
            this.json = json;
            this.snapshotRules = snapshotRules;
        }
    }

    /** UPDATE_IMAGE_PATH 消息载荷 */
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

    /** DELETE_RULE 消息载荷 */
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

    /** UPDATE_RULE 消息载荷 */
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

    // ===== 消息代码（ObserverManager 引用 CLEAN_OBSERVERS） =====
    static final int LOAD_RULES = 0x00001;
    private static final int WRITE_RULE = 0x00002;
    private static final int DELETE_RULE = 0x00004;
    private static final int DELETE_RULES = 0x00008;
    private static final int UPDATE_RULE = 0x000010;
    static final int CLEAN_OBSERVERS = 0x000020;
    private static final int CLEAN_ORPHANS = 0x000040;
    private static final int UPDATE_IMAGE_PATH = 0x000080;

    private static final long ORPHAN_CLEAN_INTERVAL = 120_000L;

    // ===== 组合的 4 个 Manager =====
    private final PermissionEnforcer mPermissionEnforcer;
    private final RuleCacheManager mCacheManager;
    private final RulePersistManager mPersistManager;
    private final ObserverManager mObserverManager;

    // ===== 基础设施 =====
    private final Logger mLogger;
    private final Handler mHandle;
    private final Context mContext;
    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();

    // ===== 状态字段 =====
    private volatile boolean mInEditMode;
    private boolean mStarted;
    private volatile boolean mDataLoaded;
    private volatile boolean mOrphanCleanPending;

    // ===== 工具栏偏好（简单字段，不需单独 Manager） =====
    private String mToolbarHiddenItems = "";

    public GodModeManagerService(Context context) {
        mLogger = Logger.getLogger("GMMService");
        mContext = context;
        // 初始化 4 个 Manager（构造注入）
        mPermissionEnforcer = new PermissionEnforcer(context);
        mCacheManager = new RuleCacheManager(mGson, mLogger);
        HandlerThread workThread = new HandlerThread("work-thread");
        workThread.start();
        mHandle = new Handler(workThread.getLooper(), this);
        mPersistManager = new RulePersistManager(mGson, mLogger, mHandle, mCacheManager);
        mObserverManager = new ObserverManager(mLogger, mHandle);
        mStarted = true;
        mLogger.i("GMMService started, loading rules from /data/system/godmode");
        mHandle.sendEmptyMessage(LOAD_RULES);
    }

    // ===================================================================
    // Handler 消息编排 — 协调各 Manager 之间的工作流
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
            case MSG_DEBOUNCE_WRITE:
                mPersistManager.handleDebouncedWrite((String) msg.obj);
                break;
        }
        return true;
    }

    private void handleWriteRule(Message msg) {
        WriteRuleMsg m = (WriteRuleMsg) msg.obj;
        if (m.snapshot != null) {
            // 带位图：删除旧图 → 保存新图 → 更新 imagePath
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
                mLogger.w("write rule aborted: save snapshot returned null");
                return;
            }
            mHandle.obtainMessage(UPDATE_IMAGE_PATH,
                    new UpdateImagePathMsg(m.packageName, m.viewRule, newImagePath)).sendToTarget();
        } else {
            // 无位图：直接持久化规则 JSON 并通知观察者
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
            mToolbarHiddenItems = mPersistManager.loadToolbarHiddenItems();
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

    // ===================================================================
    // AIDL 接口实现 — 委托给各 Manager
    // ===================================================================

    @Override
    public boolean hasLight() throws RemoteException {
        mPermissionEnforcer.enforcePermission("has light fail permission denied");
        return true;
    }

    // ---- 编辑模式 ----

    @Override
    public void setEditMode(boolean enable) throws RemoteException {
        mPermissionEnforcer.enforcePermission("set edit mode fail permission denied");
        if (!mStarted) return;
        mLogger.i("setEditMode: " + enable);
        mInEditMode = enable;
        mObserverManager.notifyObserverEditModeChanged(enable);
    }

    @Override
    public boolean isInEditMode() throws RemoteException {
        mPermissionEnforcer.enforcePermission("is in edit mode fail permission denied");
        return mInEditMode;
    }

    // ---- 观察者 ----

    @Override
    public void addObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "register observer fail permission denied");
        if (!mStarted) return;
        ActRules rules = mCacheManager.getRules(packageName);
        mObserverManager.addObserver(packageName, observer, mInEditMode, rules);
    }

    @Override
    public void removeObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "unregister observer fail permission denied");
        if (!mStarted) return;
        mObserverManager.removeObserver(packageName, observer);
    }

    // ---- 规则查询 ----

    @Override
    public AppRules getAllRules() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get all rules fail permission denied");
        if (!mStarted || !mDataLoaded) return new AppRules();
        return mCacheManager.getAllRules();
    }

    @Override
    public ActRules getRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "get rules fail permission denied");
        if (!mStarted || !mDataLoaded) return new ActRules();
        return mCacheManager.getRules(packageName);
    }

    // ---- 规则写入 ----

    @Override
    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap snapshot)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "write rule fail permission denied");
        if (!mStarted) return false;
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

    @Override
    public boolean updateRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("update rule fail permission denied");
        if (!mStarted) return false;
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

    // ---- 规则删除 ----

    @Override
    public boolean deleteRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rule fail permission denied");
        if (!mStarted) return false;
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

    @Override
    public boolean deleteRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rules fail permission denied");
        if (!mStarted) return false;
        mLogger.d("delete rules pkg=" + packageName + " size=" + mCacheManager.size());
        if (mCacheManager.deleteRules(packageName)) {
            mHandle.obtainMessage(DELETE_RULES, packageName).sendToTarget();
            return true;
        }
        return false;
    }

    // ---- 图片操作 ----

    @Override
    public String saveImageFile(String packageName, Bitmap bitmap) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "save image fail permission denied");
        if (!mStarted || bitmap == null || bitmap.isRecycled()) return null;
        try {
            return mPersistManager.saveBitmap(bitmap,
                mPersistManager.getAppDataDir(packageName));
        } catch (Exception e) {
            throw new RemoteException("Cannot access package data dir: " + e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String filePath) throws RemoteException {
        if (!mPersistManager.isValidImagePath(filePath))
            throw new RemoteException("unauthorized access " + filePath);
        File parentFile = new File(filePath).getParentFile();
        String packageFromPath = parentFile != null ? parentFile.getName() : "";
        mPermissionEnforcer.enforcePermission(
                new String[]{packageFromPath, BuildConfig.APPLICATION_ID},
                "open fd fail permission denied");
        File file = new File(filePath);
        if (!file.exists() || !file.isFile())
            throw new RemoteException("File not found: " + filePath);
        if (file.length() > 5 * 1024 * 1024)
            throw new RemoteException("File too large (>5MB): " + filePath);
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            RemoteException re = new RemoteException();
            re.initCause(e);
            throw re;
        }
    }

    // ---- 工具栏偏好 ----

    @Override
    public String getToolbarHiddenItems() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get toolbar hidden items fail permission denied");
        return mToolbarHiddenItems;
    }

    @Override
    public void setToolbarHiddenItems(String items) throws RemoteException {
        mPermissionEnforcer.enforcePermission("set toolbar prefs fail permission denied");
        mToolbarHiddenItems = items != null ? items : "";
        mPersistManager.persistToolbarHiddenItems(mToolbarHiddenItems);
    }
}
