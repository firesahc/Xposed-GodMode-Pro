package com.kaisar.xposed.godmode.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * GodMode 管理器服务 — AIDL 实现类（核心服务端点）。
 * <p>
 * 运行在 XServiceManager 注入的 SystemServer 进程中。
 * 内部持有 PermissionEnforcer、RuleCacheManager 等核心组件，
 * 通过 Handler 异步委托给各 Manager 处理规则持久化和观察者通知。
 * <p>
 * Client 调用 {@link com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient#getDefault()} 获取实例。
 */
public final class RuleServiceServer extends IGodModeManager.Stub {

    // ===== 核心组件 =====
    private final PermissionEnforcer mPermissionEnforcer;
    private final RuleCacheManager mCacheManager;
    private final WorkflowOrchestrator mOrchestrator;

    // ===== 实用工具 =====
    private final Logger mLogger;
    private final Context mContext;
    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();

    // ===== 运行时状态 =====
    private volatile boolean mInEditMode;
    private boolean mStarted;

    // ===== 工具栏隐藏项（由 RuleServiceServer 管理持久化）=====
    private String mToolbarHiddenItems = "";

    public RuleServiceServer(Context context) {
        mLogger = Logger.getLogger("RuleServiceServer");
        mContext = context;
        mPermissionEnforcer = new PermissionEnforcer(context);
        mCacheManager = new RuleCacheManager(mGson, Logger.getLogger("RuleCacheManager"));
        mOrchestrator = new WorkflowOrchestrator(mGson, Logger.getLogger("WorkflowOrchestrator"), mCacheManager,
                items -> mToolbarHiddenItems = items);
        // 将 system_server 自身日志也汇入统一日志文件 godmodepro.log
        Logger.setWriter((level, tag, msg, timestamp) -> {
            GodModeLog.write(level, "system_server", tag, msg, timestamp);
        });
        mStarted = true;
        mLogger.i("GMMService started, loading rules from /data/misc/godmode");
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
        if (!mStarted) { mLogger.w("setEditMode ignored — service not started"); return; }
        mLogger.d("setEditMode: " + enable);
        mInEditMode = enable;
        mOrchestrator.notifyEditModeChanged(enable);
    }

    @Override
    public boolean isInEditMode() throws RemoteException {
        mPermissionEnforcer.enforcePermission("is in edit mode fail permission denied");
        return mInEditMode;
    }

    // ---- 观察者管理 ----

    @Override
    public void addObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "register observer fail permission denied");
        if (!mStarted) { mLogger.w("addObserver(" + packageName + ") ignored — service not started"); return; }
        ActRules rules = mCacheManager.getRules(packageName);
        mOrchestrator.addObserver(packageName, observer, mInEditMode, rules);
    }

    @Override
    public void removeObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "unregister observer fail permission denied");
        if (!mStarted) { mLogger.w("removeObserver(" + packageName + ") ignored — service not started"); return; }
        mOrchestrator.removeObserver(packageName, observer);
    }

    // ---- 规则写入 ----

    @Override
    public AppRules getAllRules() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get all rules fail permission denied");
        if (!mStarted || !mOrchestrator.isDataLoaded()) return new AppRules();
        return mCacheManager.getAllRules();
    }

    @Override
    public ActRules getRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "get rules fail permission denied");
        if (!mStarted || !mOrchestrator.isDataLoaded()) return new ActRules();
        return mCacheManager.getRules(packageName);
    }

    // ---- 规则更新 ----

    @Override
    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap snapshot)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "write rule fail permission denied");
        if (!mStarted) { mLogger.w("writeRule(" + packageName + ") ignored — service not started"); return false; }
        return mOrchestrator.writeRuleAsync(packageName, viewRule, snapshot);
    }

    @Override
    public boolean updateRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("update rule fail permission denied");
        if (!mStarted) { mLogger.w("updateRule(" + packageName + ") ignored — service not started"); return false; }
        return mOrchestrator.updateRuleAsync(packageName, viewRule);
    }

    // ---- 规则删除 ----

    @Override
    public boolean deleteRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rule fail permission denied");
        if (!mStarted) { mLogger.w("deleteRule(" + packageName + ") ignored — service not started"); return false; }
        return mOrchestrator.deleteRuleAsync(packageName, viewRule);
    }

    @Override
    public boolean deleteRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rules fail permission denied");
        if (!mStarted) { mLogger.w("deleteRules(" + packageName + ") ignored — service not started"); return false; }
        return mOrchestrator.deleteRulesAsync(packageName);
    }

    // ---- 图片文件操作 ----

    @Override
    public String saveImageFile(String packageName, Bitmap bitmap) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "save image fail permission denied");
        if (!mStarted || bitmap == null || bitmap.isRecycled()) {
            if (!mStarted) mLogger.w("saveImageFile(" + packageName + ") ignored — service not started");
            else mLogger.w("saveImageFile(" + packageName + ") ignored — bitmap invalid");
            return null;
        }
        try {
            return mOrchestrator.saveBitmap(bitmap,
                mOrchestrator.getAppDataDir(packageName));
        } catch (Exception e) {
            throw new RemoteException("Cannot access package data dir: " + e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String filePath) throws RemoteException {
        if (!mOrchestrator.isValidImagePath(filePath))
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
        mOrchestrator.persistToolbarHiddenItems(mToolbarHiddenItems);
    }

    // ---- 日志转发 ----

    @Override
    public void log(int level, String packageName, long timestamp, String tag, String msg)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "log forward fail permission denied");
        GodModeLog.write(level, packageName, tag, msg, timestamp);
    }

    /** 关闭服务，释放工作线程资源。 */
    public void shutdown() {
        mStarted = false;
        mOrchestrator.shutdown();
    }
}
