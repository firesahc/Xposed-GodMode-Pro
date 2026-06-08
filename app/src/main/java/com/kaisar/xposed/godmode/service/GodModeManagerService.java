package com.kaisar.xposed.godmode.service;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 涓婂笣妯″紡鏍稿績绠＄悊鏈嶅姟 鈥?鎵€鏈夎法杩涚▼閫氳鍧囬€氳繃姝ゆ湇鍔°€?
 * <p>
 * 璇ユ湇鍔￠€氳繃 XServiceManager 娉ㄥ叆鍒?SystemServer 杩涚▼銆?
 * 閲囩敤缁勫悎妯″紡锛屽皢瑙勫垯缂撳瓨銆佹寔涔呭寲銆佽瀵熻€呯鐞嗐€佹潈闄愰獙璇佸鎵樼粰 4 涓笓鑱?Manager銆?
 * Handler 娑堟伅鍒嗗彂浣滀负缂栨帓灞傦紝鍗忚皟鍚?Manager 涔嬮棿鐨勫伐浣滄祦銆?
 * <p>
 * Client 绔€氳繃 {@link com.kaisar.xposed.godmode.injection.bridge.GodModeManager#getDefault()} 浣跨敤鎺ュ彛銆?
 */
public final class GodModeManagerService extends IGodModeManager.Stub {

    // ===== 缁勫悎鐨勭粍浠?=====
    private final PermissionEnforcer mPermissionEnforcer;
    private final RuleCacheManager mCacheManager;
    private final WorkflowOrchestrator mOrchestrator;

    // ===== 鍩虹璁炬柦 =====
    private final Logger mLogger;
    private final Context mContext;
    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();

    // ===== 鐘舵€佸瓧娈?=====
    private volatile boolean mInEditMode;
    private boolean mStarted;

    // ===== 宸ュ叿鏍忓亸濂斤紙绠€鍗曞瓧娈碉紝涓嶉渶鍗曠嫭 Manager锛?=====
    private String mToolbarHiddenItems = "";

    public GodModeManagerService(Context context) {
        mLogger = Logger.getLogger("GMMService");
        mContext = context;
        mPermissionEnforcer = new PermissionEnforcer(context);
        mCacheManager = new RuleCacheManager(mGson, mLogger);
        mOrchestrator = new WorkflowOrchestrator(mGson, mLogger, mCacheManager,
                items -> mToolbarHiddenItems = items);
        mStarted = true;
        mLogger.i("GMMService started, loading rules from /data/system/godmode");
    }

    // ===================================================================
    // AIDL 鎺ュ彛瀹炵幇 鈥?濮旀墭缁欏悇 Manager
    // ===================================================================

    @Override
    public boolean hasLight() throws RemoteException {
        mPermissionEnforcer.enforcePermission("has light fail permission denied");
        return true;
    }

    // ---- 缂栬緫妯″紡 ----

    @Override
    public void setEditMode(boolean enable) throws RemoteException {
        mPermissionEnforcer.enforcePermission("set edit mode fail permission denied");
        if (!mStarted) return;
        mLogger.i("setEditMode: " + enable);
        mInEditMode = enable;
        mOrchestrator.notifyEditModeChanged(enable);
    }

    @Override
    public boolean isInEditMode() throws RemoteException {
        mPermissionEnforcer.enforcePermission("is in edit mode fail permission denied");
        return mInEditMode;
    }

    // ---- 瑙傚療鑰?----

    @Override
    public void addObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "register observer fail permission denied");
        if (!mStarted) return;
        ActRules rules = mCacheManager.getRules(packageName);
        mOrchestrator.addObserver(packageName, observer, mInEditMode, rules);
    }

    @Override
    public void removeObserver(String packageName, com.kaisar.xposed.godmode.IObserver observer)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "unregister observer fail permission denied");
        if (!mStarted) return;
        mOrchestrator.removeObserver(packageName, observer);
    }

    // ---- 瑙勫垯鏌ヨ ----

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

    // ---- 瑙勫垯鍐欏叆 ----

    @Override
    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap snapshot)
            throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "write rule fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.writeRuleAsync(packageName, viewRule, snapshot);
    }

    @Override
    public boolean updateRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("update rule fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.updateRuleAsync(packageName, viewRule);
    }

    // ---- 瑙勫垯鍒犻櫎 ----

    @Override
    public boolean deleteRule(String packageName, RuleRecord viewRule) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rule fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.deleteRuleAsync(packageName, viewRule);
    }

    @Override
    public boolean deleteRules(String packageName) throws RemoteException {
        mPermissionEnforcer.enforcePermission("delete rules fail permission denied");
        if (!mStarted) return false;
        return mOrchestrator.deleteRulesAsync(packageName);
    }

    // ---- 鍥剧墖鎿嶄綔 ----

    @Override
    public String saveImageFile(String packageName, Bitmap bitmap) throws RemoteException {
        mPermissionEnforcer.enforcePermission(
                new String[]{packageName, BuildConfig.APPLICATION_ID},
                "save image fail permission denied");
        if (!mStarted || bitmap == null || bitmap.isRecycled()) return null;
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

    // ---- 宸ュ叿鏍忓亸濂?----

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
}
