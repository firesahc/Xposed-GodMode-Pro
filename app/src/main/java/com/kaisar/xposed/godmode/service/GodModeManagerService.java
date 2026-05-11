package com.kaisar.xposed.godmode.service;


import static com.kaisar.xposed.godmode.injection.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.injection.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.injection.util.FileUtils.S_IRWXU;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.kaisar.xposed.godmode.BuildConfig;
import com.kaisar.xposed.godmode.IGodModeManager;
import com.kaisar.xposed.godmode.IObserver;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.util.FileUtils;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.ViewRule;
import com.kaisar.xposed.godmode.util.Preconditions;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


/**
 * Created by jrsen on 17-10-15.
 * 上帝模式核心管理服务所有跨进程通讯均通过此服务
 * 该服务通过Xposed注入到SystemServer进程作为一个系统服务
 * Client端可以使用{@link GodModeManager#getDefault()}使用该服务提供的接口
 */

public final class GodModeManagerService extends IGodModeManager.Stub implements Handler.Callback {

    // /data/system/godmode
    private static final String BASE_DIR = String.format("%s/misc/%s", Environment.getDataDirectory().getAbsolutePath(), "godmode");
    // /data/system/godmode/{package}/package.rule
    private static final String RULE_FILE_SUFFIX = ".rule";
    // /data/system/godmode/{package}/xxxxxxxxx.webp
    private static final String IMAGE_FILE_SUFFIX = ".webp";

    private static final int WRITE_RULE = 0x00002;
    private static final int DELETE_RULE = 0x00004;
    private static final int DELETE_RULES = 0x00008;
    private static final int UPDATE_RULE = 0x000016;
    private static final int CLEAN_OBSERVERS = 0x000032;
    private static final int LOAD_RULES = 0x00001;
    private static final int CLEAN_ORPHANS = 0x000064;
    private static final int UPDATE_IMAGE_PATH = 0x000128;
    private static final long OBSERVER_CLEAN_INTERVAL = 60_000L;
    private static final long ORPHAN_CLEAN_INTERVAL = 120_000L;

    private final Logger mLogger;
    private final RemoteCallbackList<ObserverProxy> mRemoteCallbackList = new RemoteCallbackList<>();
    private final AppRules mAppRulesCache = new AppRules();
    private final Context mContext;
    private final Handler mHandle;
    private boolean mInEditMode;
    private boolean mStarted;
    private volatile boolean mDataLoaded;
    private volatile boolean mOrphanCleanPending;

    private final Gson mGson = new GsonBuilder().setPrettyPrinting().create();
    private final HashMap<String, IBinder> mRegisteredObserverMap = new HashMap<>();

    public GodModeManagerService(Context context) {
        mLogger = Logger.getLogger("GMMService");
        mContext = context;
        HandlerThread workThread = new HandlerThread("work-thread");
        workThread.start();
        mHandle = new Handler(workThread.getLooper(), this);
        mStarted = true;
        mHandle.sendEmptyMessage(LOAD_RULES);
    }

    private void loadRuleData() throws IOException {
        File dataDir = new File(getBaseDir());
        File[] packageDirs = dataDir.listFiles(File::isDirectory);
        if (packageDirs != null && packageDirs.length > 0) {
            HashMap<String, ActRules> appRules = new HashMap<>();
            for (File packageDir : packageDirs) {
                try {
                    String packageName = packageDir.getName();
                    String appRuleFile = getAppRuleFilePath(packageName);
                    String json = FileUtils.readTextFile(appRuleFile, 0, null);
                    ActRules rules = mGson.fromJson(json, ActRules.class);
                    Preconditions.checkNotNull(rules, "rules is null");
                    //compact rule
                    Iterator<Map.Entry<String, List<ViewRule>>> iterator = rules.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<String, List<ViewRule>> listEntry = iterator.next();
                        List<ViewRule> value = listEntry.getValue();
                        if (value == null || value.isEmpty()) {
                            iterator.remove();
                        }
                    }
                    if (rules.isEmpty()) {
                        FileUtils.delete(packageDir);
                        continue;
                    }
                    appRules.put(packageName, rules);
                } catch (IOException e) {
                    mLogger.w("load rule fail", e);
                } catch (NullPointerException | JsonSyntaxException e) {
                    mLogger.e("load rule error", e);
                    FileUtils.delete(packageDir);
                }
            }
            mAppRulesCache.putAll(appRules);
            mLogger.d("app rules cache=" + mAppRulesCache.size());
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case WRITE_RULE: {
                try {
                    Object[] args = (Object[]) msg.obj;
                    String packageName = (String) args[0];
                    ViewRule viewRule = (ViewRule) args[1];
                    Bitmap snapshot = (Bitmap) args[2];
                    String oldImagePath = args.length > 3 ? (String) args[3] : null;
                    if (snapshot != null) {
                        if (oldImagePath != null && !TextUtils.isEmpty(oldImagePath)) {
                            FileUtils.delete(oldImagePath);
                        }
                        String newImagePath = saveBitmap(snapshot, getAppDataDir(packageName));
                        if (newImagePath == null) {
                            mLogger.w("write rule aborted: save snapshot failed", (Throwable) null);
                            break;
                        }
                        mHandle.obtainMessage(UPDATE_IMAGE_PATH,
                                new Object[]{packageName, viewRule, newImagePath}).sendToTarget();
                    } else {
                        String json = (String) args[4];
                        ActRules snapshotRules = (ActRules) args[5];
                        safePersistRules(packageName, json);
                        scheduleOrphanCleanup();
                        notifyObserverRuleChanged(packageName, snapshotRules);
                    }
                } catch (IOException e) {
                    mLogger.w("write rule failed", e);
                }
            }
            break;
            case UPDATE_IMAGE_PATH: {
                try {
                    Object[] args = (Object[]) msg.obj;
                    String packageName = (String) args[0];
                    ViewRule viewRule = (ViewRule) args[1];
                    String newImagePath = (String) args[2];
                    String json;
                    ActRules snapshotRules;
                    synchronized (mAppRulesCache) {
                        ActRules actRules = mAppRulesCache.get(packageName);
                        if (actRules != null) {
                            List<ViewRule> rules = actRules.get(viewRule.activityClass);
                            if (rules != null) {
                                int idx = rules.indexOf(viewRule);
                                if (idx >= 0) {
                                    rules.get(idx).imagePath = newImagePath;
                                }
                            }
                        }
                        json = mGson.toJson(actRules);
                        snapshotRules = snapshotActRules(actRules);
                    }
                    safePersistRules(packageName, json);
                    scheduleOrphanCleanup();
                    notifyObserverRuleChanged(packageName, snapshotRules);
                } catch (IOException e) {
                    mLogger.w("update image path failed", e);
                }
            }
            break;
            case DELETE_RULE: {
                try {
                    Object[] args = (Object[]) msg.obj;
                    String packageName = (String) args[0];
                    String json = (String) args[1];
                    ActRules snapshotRules = (ActRules) args[2];
                    String imagePath = (String) args[3];
                    FileUtils.delete(imagePath);
                    safePersistRules(packageName, json);
                    scheduleOrphanCleanup();
                    notifyObserverRuleChanged(packageName, snapshotRules);
                } catch (IOException e) {
                    mLogger.w("delete rule failed", e);
                }
            }
            break;
            case DELETE_RULES: {
                try {
                    String packageName = (String) msg.obj;
                    FileUtils.delete(getAppDataDir(packageName));
                    notifyObserverRuleChanged(packageName, new ActRules());
                } catch (FileNotFoundException e) {
                    mLogger.w("delete rules failed", e);
                }
            }
            break;
            case UPDATE_RULE: {
                try {
                    Object[] args = (Object[]) msg.obj;
                    String packageName = (String) args[0];
                    String json = (String) args[1];
                    ActRules snapshotRules = (ActRules) args[2];
                    safePersistRules(packageName, json);
                    notifyObserverRuleChanged(packageName, snapshotRules);
                } catch (IOException e) {
                    mLogger.w("update rule failed", e);
                }
                break;
            }
            case CLEAN_OBSERVERS: {
                cleanDeadObservers();
                mHandle.sendEmptyMessageDelayed(CLEAN_OBSERVERS, OBSERVER_CLEAN_INTERVAL);
                break;
            }
            case LOAD_RULES: {
                try {
                    loadRuleData();
                    mDataLoaded = true;
                    mLogger.i("rule data loaded: " + mAppRulesCache.size() + " packages");
                } catch (Exception e) {
                    mLogger.e("loadRuleData failed: " + BASE_DIR, e);
                    mDataLoaded = true;
                }
                break;
            }
            case CLEAN_ORPHANS: {
                mOrphanCleanPending = false;
                try {
                    cleanAllOrphanImages();
                } catch (Exception e) {
                    mLogger.w("orphan cleanup failed", e);
                }
                break;
            }
            default: {
            }
            break;
        }
        return true;
    }

    private void safePersistRules(String packageName, String json) throws IOException {
        File appDataDir = new File(getBaseDir(), packageName);
        if (!appDataDir.exists() && !appDataDir.mkdirs()) {
            throw new IOException("Failed to create dir: " + appDataDir);
        }
        File ruleFile = new File(appDataDir, packageName + RULE_FILE_SUFFIX);
        File tmpFile = new File(appDataDir, packageName + RULE_FILE_SUFFIX + ".tmp");
        FileUtils.stringToFile(tmpFile, json);
        if (!tmpFile.renameTo(ruleFile)) {
            if (tmpFile.exists() && !tmpFile.delete()) {
                mLogger.w("Failed to delete tmp file: " + tmpFile, (Throwable) null);
            }
            throw new IOException("Failed to atomically rename rule file: " + ruleFile);
        }
        FileUtils.setPermissions(ruleFile, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
    }

    private void scheduleOrphanCleanup() {
        if (!mOrphanCleanPending) {
            mOrphanCleanPending = true;
            mHandle.sendEmptyMessageDelayed(CLEAN_ORPHANS, ORPHAN_CLEAN_INTERVAL);
        }
    }

    private ActRules snapshotActRules(ActRules source) {
        if (source == null) return new ActRules();
        ActRules copy = new ActRules();
        for (Map.Entry<String, List<ViewRule>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    private void cleanAllOrphanImages() {
        try {
            File dataDir = new File(getBaseDir());
            File[] packageDirs = dataDir.listFiles(File::isDirectory);
            if (packageDirs == null) return;
            for (File packageDir : packageDirs) {
                File[] imageFiles = packageDir.listFiles((dir, name) -> name.endsWith(IMAGE_FILE_SUFFIX));
                if (imageFiles == null || imageFiles.length == 0) continue;
                java.util.Set<String> referenced = new java.util.HashSet<>();
                synchronized (mAppRulesCache) {
                    ActRules actRules = mAppRulesCache.get(packageDir.getName());
                    if (actRules != null) {
                        for (List<ViewRule> rules : actRules.values()) {
                            for (ViewRule rule : rules) {
                                if (!TextUtils.isEmpty(rule.imagePath)) referenced.add(rule.imagePath);
                                if (!TextUtils.isEmpty(rule.modImagePath)) referenced.add(rule.modImagePath);
                            }
                        }
                    }
                }
                for (File f : imageFiles) {
                    if (!referenced.contains(f.getAbsolutePath())) {
                        FileUtils.delete(f);
                    }
                }
            }
        } catch (FileNotFoundException e) {
            mLogger.w("orphan cleanup: base dir not found", e);
        }
    }

    private void cleanDeadObservers() {
        synchronized (mRemoteCallbackList) {
            int N = mRemoteCallbackList.beginBroadcast();
            List<ObserverProxy> dead = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                ObserverProxy proxy = mRemoteCallbackList.getBroadcastItem(i);
                if (proxy == null || !proxy.observer.asBinder().pingBinder()) {
                    dead.add(proxy);
                }
            }
            mRemoteCallbackList.finishBroadcast();
            for (ObserverProxy proxy : dead) {
                if (proxy != null) {
                    try {
                        mRemoteCallbackList.unregister(proxy);
                        synchronized (mRegisteredObserverMap) {
                            mRegisteredObserverMap.remove(proxy.packageName, proxy.observer.asBinder());
                        }
                        mLogger.d("cleaned dead observer: " + proxy.packageName);
                    } catch (Exception e) {
                        mLogger.w("clean dead observer failed", e);
                    }
                }
            }
        }
    }

    private boolean checkPermission(@NonNull String permPackage) {
        int callingUid = Binder.getCallingUid();
        String[] packagesForUid = mContext.getPackageManager().getPackagesForUid(callingUid);
        return packagesForUid != null && Arrays.asList(packagesForUid).contains(permPackage);
    }

    private void enforcePermission(@NonNull String[] permPackages, String message) throws RemoteException {
        for (String permPackage : permPackages) {
            if (checkPermission(permPackage)) {
                return;
            }
        }
        throw new RemoteException(message);
    }

    private void enforcePermission(String message) throws RemoteException {
        if (!checkPermission(BuildConfig.APPLICATION_ID)) {
            throw new RemoteException(message);
        }
    }

    @Override
    public boolean hasLight() {
        return true;
    }

    /**
     * Set edit mode
     *
     * @param enable enable or disable
     */
    @Override
    public void setEditMode(boolean enable) throws RemoteException {
        enforcePermission("set edit mode fail permission denied");
        if (!mStarted) return;
        mLogger.i("[GodMode] setEditMode: " + enable);
        mInEditMode = enable;
        notifyObserverEditModeChanged(enable);
    }

    /**
     * Check in edit mode
     *
     * @return enable or disable
     */
    @Override
    public boolean isInEditMode() {
        return mInEditMode;
    }

    /**
     * Register an observer to be notified when status changed.
     *
     * @param packageName package name
     * @param observer    client observer
     */
    @Override
    public void addObserver(String packageName, IObserver observer) throws RemoteException {
        enforcePermission(new String[]{packageName, BuildConfig.APPLICATION_ID}, "register observer fail permission denied");
        if (!mStarted) return;
        synchronized (mRemoteCallbackList) {
            synchronized (mRegisteredObserverMap) {
                IBinder binder = observer.asBinder();
                if (mRegisteredObserverMap.containsKey(packageName)
                        && mRegisteredObserverMap.get(packageName) == binder) {
                    mLogger.d("observer already registered for: " + packageName);
                    return;
                }
                mRegisteredObserverMap.put(packageName, binder);
            }
            mRemoteCallbackList.register(new ObserverProxy(packageName, observer));
            if (!mHandle.hasMessages(CLEAN_OBSERVERS)) {
                mHandle.sendEmptyMessageDelayed(CLEAN_OBSERVERS, OBSERVER_CLEAN_INTERVAL);
            }
        }
        try {
            observer.onEditModeChanged(mInEditMode);
            ActRules rules;
            synchronized (mAppRulesCache) {
                rules = mAppRulesCache.containsKey(packageName) ? mAppRulesCache.get(packageName) : new ActRules();
            }
            observer.onViewRuleChanged(packageName, rules);
        } catch (RemoteException e) {
            mLogger.w("immediate notify observer failed", e);
        }
    }

    /**
     * Unregister an observer
     *
     * @param packageName package name
     * @param observer    client observer
     * @throws RemoteException nothing
     */
    @Override
    public void removeObserver(String packageName, IObserver observer) throws RemoteException {
        enforcePermission(new String[]{packageName, BuildConfig.APPLICATION_ID}, "unregister observer fail permission denied");
        if (!mStarted) return;
        synchronized (mRemoteCallbackList) {
            mRemoteCallbackList.unregister(new ObserverProxy(packageName, observer));
            synchronized (mRegisteredObserverMap) {
                mRegisteredObserverMap.remove(packageName);
            }
        }
    }

    /**
     * Get all packages rules
     *
     * @return packages rules
     */
    @Override
    public AppRules getAllRules() throws RemoteException {
        enforcePermission("get all rules fail permission denied");
        if (!mStarted || !mDataLoaded) return new AppRules();
        synchronized (mAppRulesCache) {
            AppRules copy = new AppRules();
            copy.putAll(mAppRulesCache);
            return copy;
        }
    }

    @Override
    public ActRules getRules(String packageName) throws RemoteException {
        enforcePermission(new String[]{packageName, BuildConfig.APPLICATION_ID}, "get rules fail permission denied");
        if (!mStarted || !mDataLoaded) return new ActRules();
        synchronized (mAppRulesCache) {
            return mAppRulesCache.containsKey(packageName) ? mAppRulesCache.get(packageName) : new ActRules();
        }
    }

    /**
     * Write or update a rule (remove or modify). Replaces existing rule for the same view.
     */
    @Override
    public boolean writeRule(String packageName, ViewRule viewRule, Bitmap snapshot) throws RemoteException {
        enforcePermission(new String[]{packageName, BuildConfig.APPLICATION_ID}, "write rule fail permission denied");
        if (!mStarted) return false;
        synchronized (mAppRulesCache) {
            try {
                ActRules actRules = mAppRulesCache.get(packageName);
                if (actRules == null) {
                    mAppRulesCache.put(packageName, actRules = new ActRules());
                }
                List<ViewRule> viewRules = actRules.computeIfAbsent(viewRule.activityClass, k -> new ArrayList<>());
                int index = viewRules.indexOf(viewRule);
                String oldImagePath = null;
                if (index >= 0) {
                    oldImagePath = viewRules.get(index).imagePath;
                    viewRules.set(index, viewRule);
                } else {
                    viewRules.add(viewRule);
                }
                if (snapshot != null) {
                    mHandle.obtainMessage(WRITE_RULE,
                            new Object[]{packageName, viewRule, snapshot, oldImagePath}).sendToTarget();
                } else {
                    String json = mGson.toJson(actRules);
                    ActRules snapshotRules = snapshotActRules(actRules);
                    mHandle.obtainMessage(WRITE_RULE,
                            new Object[]{packageName, viewRule, null, null, json, snapshotRules}).sendToTarget();
                }
                return true;
            } catch (Exception e) {
                mLogger.w("write rule failed", e);
                return false;
            }
        }
    }

    /**
     * Update rule of package
     *
     * @param packageName package name of the rule
     * @param viewRule    rule object
     * @return success or fail
     */
    @Override
    public boolean updateRule(String packageName, ViewRule viewRule) throws RemoteException {
        enforcePermission("update rule fail permission denied");
        if (!mStarted) return false;
        synchronized (mAppRulesCache) {
            try {
                ActRules actRules = mAppRulesCache.get(packageName);
                if (actRules == null) {
                    mAppRulesCache.put(packageName, actRules = new ActRules());
                }
                List<ViewRule> viewRules = actRules.computeIfAbsent(viewRule.activityClass, k -> new ArrayList<>());
                int index = viewRules.indexOf(viewRule);
                if (index >= 0) {
                    viewRules.set(index, viewRule);
                } else {
                    viewRules.add(viewRule);
                }
                String json = mGson.toJson(actRules);
                ActRules snapshotRules = snapshotActRules(actRules);
                mHandle.obtainMessage(UPDATE_RULE,
                        new Object[]{packageName, json, snapshotRules}).sendToTarget();
                return true;
            } catch (Exception e) {
                mLogger.w("update rule failed", e);
                return false;
            }
        }
    }

    /**
     * Delete the single rule of package
     *
     * @param packageName package name of the rule
     * @param viewRule    rule object
     * @return success or fail
     */
    @Override
    public boolean deleteRule(String packageName, ViewRule viewRule) throws RemoteException {
        enforcePermission("delete rule fail permission denied");
        if (!mStarted) return false;
        synchronized (mAppRulesCache) {
            try {
                ActRules actRules = Preconditions.checkNotNull(mAppRulesCache.get(packageName), "not found this rule can't delete.");
                List<ViewRule> viewRules = Preconditions.checkNotNull(actRules.get(viewRule.activityClass), "not found this rule can't delete.");
                boolean removed = viewRules.remove(viewRule);
                if (removed) {
                    if (viewRules.isEmpty()) {
                        actRules.remove(viewRule.activityClass);
                        if (actRules.isEmpty()) {
                            mAppRulesCache.remove(packageName);
                        }
                    }
                    String json = mGson.toJson(actRules);
                    ActRules snapshotRules = snapshotActRules(actRules);
                    mHandle.obtainMessage(DELETE_RULE,
                            new Object[]{packageName, json, snapshotRules, viewRule.imagePath}).sendToTarget();
                }
                return removed;
            } catch (Exception e) {
                mLogger.w("delete rule failed", e);
                return false;
            }
        }
    }

    /**
     * Delete all rules of package
     *
     * @param packageName package name of the rule
     * @return success or fail
     */
    @Override
    public boolean deleteRules(String packageName) throws RemoteException {
        enforcePermission("delete rules fail permission denied");
        if (!mStarted) return false;
        synchronized (mAppRulesCache) {
            mLogger.d("delete rules pkg=" + packageName + " cache=" + mAppRulesCache);
            if (mAppRulesCache.containsKey(packageName)) {
                mAppRulesCache.remove(packageName);
                mHandle.obtainMessage(DELETE_RULES, packageName).sendToTarget();
                return true;
            }
            return false;
        }
    }

    @Override
    public String saveImageFile(String packageName, Bitmap bitmap) throws RemoteException {
        enforcePermission(new String[]{packageName, BuildConfig.APPLICATION_ID}, "save image fail permission denied");
        if (!mStarted || bitmap == null || bitmap.isRecycled()) return null;
        try {
            return saveBitmap(bitmap, getAppDataDir(packageName));
        } catch (FileNotFoundException e) {
            throw new RemoteException("Cannot access package data dir: " + e.getMessage());
        }
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String filePath) throws RemoteException {
        if (!filePath.startsWith(BASE_DIR) || !filePath.endsWith(IMAGE_FILE_SUFFIX))
            throw new RemoteException(String.format("unauthorized access %s", filePath));
        File parentFile = new File(filePath).getParentFile();
        String packageFromPath = parentFile != null ? parentFile.getName() : "";
        enforcePermission(new String[]{packageFromPath, BuildConfig.APPLICATION_ID},
                "open fd fail permission denied");
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new RemoteException("File not found: " + filePath);
        }
        if (file.length() > 5 * 1024 * 1024) {
            throw new RemoteException("File too large (>5MB): " + filePath);
        }
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            RemoteException remoteException = new RemoteException();
            remoteException.initCause(e);
            throw remoteException;
        }
    }

    private String saveBitmap(Bitmap bitmap, String dir) {
        try {
            Bitmap bitmapToSave = bitmap;
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                bitmapToSave = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                new Canvas(bitmapToSave).drawBitmap(bitmap, 0, 0, null);
            }
            File file = new File(dir, System.currentTimeMillis() + IMAGE_FILE_SUFFIX);
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (bitmapToSave.compress(Bitmap.CompressFormat.WEBP, 80, out)) {
                    FileUtils.setPermissions(file, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
                    return file.getAbsolutePath();
                }
                throw new FileNotFoundException("bitmap can't compress to " + file.getAbsolutePath());
            } finally {
                if (bitmapToSave != bitmap && !bitmapToSave.isRecycled()) {
                    bitmapToSave.recycle();
                }
            }
        } catch (IOException e) {
            mLogger.w("save bitmap fail", e);
            return null;
        }
    }

    private void notifyObserverRuleChanged(String packageName, ActRules actRules) {
        forEachLiveObserver((proxy) -> {
            if (TextUtils.equals(proxy.packageName, packageName) || TextUtils.equals(proxy.packageName, "*")) {
                proxy.observer.onViewRuleChanged(packageName, actRules);
            }
        });
    }

    private void notifyObserverEditModeChanged(boolean enable) {
        forEachLiveObserver((proxy) -> proxy.onEditModeChanged(enable));
    }

    private void forEachLiveObserver(ObserverAction action) {
        synchronized (mRemoteCallbackList) {
            final int N = mRemoteCallbackList.beginBroadcast();
            for (int i = 0; i < N; i++) {
                try {
                    ObserverProxy proxy = mRemoteCallbackList.getBroadcastItem(i);
                    if (proxy != null && proxy.observer.asBinder().pingBinder()) {
                        action.execute(proxy);
                    }
                } catch (Exception e) {
                    mLogger.w("notify observer failed", e);
                }
            }
            mRemoteCallbackList.finishBroadcast();
        }
    }

    private interface ObserverAction {
        void execute(ObserverProxy proxy) throws RemoteException;
    }

    private String getBaseDir() throws FileNotFoundException {
        mLogger.d(BASE_DIR);
        File dir = new File(BASE_DIR);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    private String getAppDataDir(String packageName) throws FileNotFoundException {
        File dir = new File(getBaseDir(), packageName);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    private String getAppRuleFilePath(String packageName) throws IOException {
        File file = new File(getAppDataDir(packageName), packageName + RULE_FILE_SUFFIX);
        if (file.exists() || file.createNewFile()) {
            FileUtils.setPermissions(file, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return file.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    private static final class ObserverProxy implements IObserver {

        private final String packageName;
        private final IObserver observer;

        public ObserverProxy(String packageName, IObserver observer) {
            this.packageName = packageName;
            this.observer = observer;
        }

        @Override
        public void onEditModeChanged(boolean enable) throws RemoteException {
            observer.onEditModeChanged(enable);
        }

        @Override
        public void onViewRuleChanged(String packageName, ActRules actRules) throws RemoteException {
            observer.onViewRuleChanged(packageName, actRules);
        }

        @Override
        public IBinder asBinder() {
            return observer.asBinder();
        }
    }

}
