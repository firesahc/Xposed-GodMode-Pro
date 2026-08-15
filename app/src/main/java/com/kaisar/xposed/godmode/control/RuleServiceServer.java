package com.kaisar.xposed.godmode.control;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.engine.util.GmConstants;
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
 * 内部持有 {@link RuleRepository}、{@link ObserverRegistry}、{@link ModuleLifecycle} 等核心组件。
 */
public final class RuleServiceServer extends IGodModeManager.Stub {

    // ===== 核心组件 =====
    private final PermissionEnforcer mPermissionEnforcer;
    private final RuleRepository mRepository;
    private final ObserverRegistry mObserverRegistry;
    private final ModuleLifecycle mLifecycle;

    // ===== 实用工具 =====
    private final Logger mLogger;
    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();

    // ===== 运行时状态 =====
    private volatile boolean mInEditMode;
    private volatile boolean mStarted;

    // ===== 工具栏隐藏项 =====
    private String mToolbarHiddenItems = "";

    public RuleServiceServer(Context context) {
        mLogger = Logger.getLogger("RuleServiceServer");
        mPermissionEnforcer = new PermissionEnforcer(context);

        // 创建 Handler 供 ObserverRegistry 使用
        HandlerThread handlerThread = new HandlerThread("control-handler");
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        // 创建观察者注册表
        mObserverRegistry = new ObserverRegistry(
                Logger.getLogger("ObserverRegistry"), handler, 0x0020);

        // 创建模块生命周期
        mLifecycle = new ModuleLifecycle(
                ModuleLifecycle.Layer.CONTROL);
        mLifecycle.transition(ModuleLifecycle.State.LOADING);

        // 创建规则仓库
        mRepository = new RuleRepository(
                mGson,
                Logger.getLogger("RuleRepository"),
                mObserverRegistry
        );

        // 将 system_server 自身日志也汇入统一日志文件 godmodepro.log
        Logger.setWriter((level, tag, msg, timestamp) -> {
            GodModeLog.write(level, "system_server", tag, msg, timestamp);
        });

        // 加载数据；完成后再标记控制面健康
        mRepository.loadAll(
                () -> mLifecycle.markHealthy(ModuleLifecycle.Layer.CONTROL),
                () -> mLifecycle.markError(ModuleLifecycle.Layer.CONTROL, "load rules failed"));

        // 加载工具栏偏好
        mToolbarHiddenItems = mRepository.loadToolbarHiddenItems();

        mStarted = true;
        mLogger.i("GMMService started, loading rules from persistent storage");
    }

    // ===================================================================
    // AIDL 接口实现
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
        mObserverRegistry.notifyObserverEditModeChanged(enable);
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
        ActRules rules = mRepository.getRules(packageName);
        mObserverRegistry.addObserver(packageName, observer, mInEditMode, rules);
    }

    @Override
    public void removeObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "unregister observer fail permission denied");
        if (!mStarted) { mLogger.w("removeObserver(" + packageName + ") ignored — service not started"); return; }
        mObserverRegistry.removeObserver(packageName, observer);
    }

    // ---- 规则读取 ----

    @Override
    public AppRules getAllRules() throws RemoteException {
        mPermissionEnforcer.enforcePermission("get all rules fail permission denied");
        if (!mStarted) {
            mLogger.w("getAllRules: service not started");
            return new AppRules();
        }
        if (!mRepository.isDataLoaded()) {
            mLogger.w("getAllRules: data not loaded yet");
            return new AppRules();
        }
        return mRepository.getAllRules();
    }

    @Override
    public ActRules getRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "get rules fail permission denied");
        if (!mStarted) {
            mLogger.w("getRules: service not started");
            return new ActRules();
        }
        if (!mRepository.isDataLoaded()) {
            mLogger.w("getRules: data not loaded yet");
            return new ActRules();
        }
        return mRepository.getRules(packageName);
    }

    // ---- 规则更新 ----

    @Override
    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap snapshot)
            throws RemoteException {
        // Keep the existing write contract (the target package is supplied separately),
        // while still rejecting malformed filesystem scopes and unauthorized callers.
        enforcePackageCaller(packageName, "write rule");
        if (!mStarted) { mLogger.w("writeRule(" + packageName + ") ignored — service not started"); return false; }
        return mRepository.writeRule(packageName, viewRule, snapshot);
    }

    @Override
    public boolean updateRule(String packageName, RuleRecord viewRule) throws RemoteException {
        enforceRulePackage(packageName, viewRule, "update rule");
        if (!mStarted) { mLogger.w("updateRule(" + packageName + ") ignored — service not started"); return false; }
        return mRepository.updateRule(packageName, viewRule);
    }

    // ---- 规则删除 ----

    @Override
    public boolean deleteRule(String packageName, RuleRecord viewRule) throws RemoteException {
        enforceRulePackage(packageName, viewRule, "delete rule");
        if (!mStarted) { mLogger.w("deleteRule(" + packageName + ") ignored — service not started"); return false; }
        return mRepository.deleteRule(packageName, viewRule);
    }

    @Override
    public boolean deleteRules(String packageName) throws RemoteException {
        enforcePackageCaller(packageName, "delete rules");
        if (!mStarted) { mLogger.w("deleteRules(" + packageName + ") ignored — service not started"); return false; }
        return mRepository.deleteRules(packageName);
    }

    // ---- 图片文件操作 ----

    @Override
    public String saveImageFile(String packageName, Bitmap bitmap) throws RemoteException {
        enforcePackageCaller(packageName, "save image");
        if (!mStarted || bitmap == null || bitmap.isRecycled()) {
            if (!mStarted) mLogger.w("saveImageFile(" + packageName + ") ignored — service not started");
            else mLogger.w("saveImageFile(" + packageName + ") ignored — bitmap invalid");
            return null;
        }
        try {
            return mRepository.saveBitmap(bitmap, mRepository.getAppDataDir(packageName));
        } catch (Exception e) {
            throw new RemoteException("Cannot access package data dir: " + e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String filePath) throws RemoteException {
        if (!mRepository.isValidImagePath(filePath))
            throw new RemoteException("unauthorized access " + filePath);
        File parentFile = new File(filePath).getParentFile();
        String packageFromPath = parentFile != null ? parentFile.getName() : "";
        mPermissionEnforcer.enforcePermission(
                new String[]{packageFromPath, BuildConfig.APPLICATION_ID},
                "open fd fail permission denied");
        File file = new File(filePath);
        if (!file.exists() || !file.isFile())
            throw new RemoteException("File not found: " + filePath);
        if (file.length() > GmConstants.MAX_IMAGE_FILE_SIZE_BYTES)
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
        mPermissionEnforcer.enforcePermission("set toolbar hidden items fail permission denied");
        mToolbarHiddenItems = items != null ? items : "";
        mRepository.persistToolbarHiddenItems(mToolbarHiddenItems);
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
        mRepository.shutdown();
    }

    private void enforceRulePackage(String packageName, RuleRecord viewRule, String operation)
            throws RemoteException {
        if (viewRule == null || !packageNameEquals(packageName, viewRule.packageName)) {
            mLogger.w(operation + " rejected: package scope mismatch, package=" + packageName);
            throw new RemoteException(operation + " fail invalid package scope");
        }
        enforcePackageCaller(packageName, operation);
    }

    private void enforcePackageCaller(String packageName, String operation)
            throws RemoteException {
        if (!PackageNameValidator.isValid(packageName)) {
            throw new RemoteException(operation + " fail invalid package name");
        }
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                operation + " fail permission denied");
    }

    private static boolean packageNameEquals(String left, String right) {
        return left != null && left.equals(right);
    }
}
