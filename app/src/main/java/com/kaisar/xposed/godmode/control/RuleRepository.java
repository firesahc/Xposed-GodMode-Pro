package com.kaisar.xposed.godmode.control;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;
import static com.kaisar.xposed.godmode.engine.util.GmConstants.DATA_DIR;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.AppRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 规则仓库 — 缓存 + 持久化 + 发布的统一入口。
 * <p>
 * 统一承接规则缓存、持久化调度和控制面 CRUD 职责。
 * <p>
 * 内部结构：
 * <ul>
 *   <li>{@link RuleCache} — 基于 ReentrantReadWriteLock 的线程安全内存缓存</li>
 *   <li>{@link RuleStore} — JSON 文件持久化 + 图片保存 + 孤儿清理</li>
 * </ul>
 * <p>
 * 写操作异步执行（HandlerThread），读操作同步返回。
 * <p>
 * 规则发布走 Binder 即时推送（{@link ObserverRegistry#notifyObserverRuleChanged}），
 * 不再依赖文件快照链路。
 */
public final class RuleRepository {

    private static final String TAG = "RuleRepository";

    // ===== 消息码 =====
    private static final int MSG_WRITE_RULE = 0x0001;
    private static final int MSG_DELETE_RULE = 0x0002;
    private static final int MSG_DELETE_RULES = 0x0003;
    private static final int MSG_DEBOUNCE_WRITE = 0x0004;
    private static final int MSG_CLEAN_ORPHANS = 0x0005;

    private static final long ORPHAN_CLEAN_INTERVAL = 120_000L;

    private final RuleCache mCache;
    private final RuleStore mStore;
    private final ObserverRegistry mObserverRegistry;
    private final Gson mGson;
    private final Logger mLogger;

    // ===== 异步处理 =====
    private final HandlerThread mWorkThread;
    private final Handler mHandle;
    private volatile boolean mDataLoaded;

    // ===== 构造 =====

    public RuleRepository(Gson gson, Logger logger,
                          ObserverRegistry observerRegistry) {
        this.mGson = gson;
        this.mLogger = logger;
        this.mObserverRegistry = observerRegistry;
        this.mCache = new RuleCache(gson, logger);

        mWorkThread = new HandlerThread("rule-repository");
        mWorkThread.start();
        mHandle = new Handler(mWorkThread.getLooper(), this::handleMessage);
        this.mStore = new RuleStore(gson, logger, mCache, mHandle);
    }

    // ===== 读操作 =====

    /** 返回所有规则的防御性副本 */
    public AppRules getAllRules() {
        return mCache.getAllRules();
    }

    /** 返回指定包的规则副本（不存在则返回空 ActRules） */
    public ActRules getRules(String packageName) {
        return mCache.getRules(packageName);
    }

    /** 查询某规则在缓存中的旧 imagePath */
    public String getOldImagePath(String packageName, RuleRecord viewRule) {
        return mCache.getOldImagePath(packageName, viewRule);
    }

    // ===== 写操作 =====

    /**
     * 异步写入规则（带快照或纯 JSON）。
     * <p>
     * 两路分支：
     * <ul>
     *   <li><b>带快照</b>：handler 中先执行 I/O（saveBitmap），成功后再更新缓存 + 持久化 + 双通道发布。</li>
     *   <li><b>纯 JSON</b>：同步更新缓存后 handler 只做持久化 + 双通道发布。</li>
     * </ul>
     */
    public boolean writeRule(String packageName, RuleRecord viewRule, Bitmap snapshot) {
        if (!PackageNameValidator.isValid(packageName) || viewRule == null) return false;
        try {
            Object writeMsg;
            if (snapshot != null) {
                // Capture the request generation before deferred bitmap I/O. A delete
                // arriving while the worker is busy must be able to invalidate this write.
                writeMsg = new WriteMessage(packageName, viewRule, snapshot, null, null, null,
                        mCache.nextGeneration());
            } else {
                RuleCache.CacheResult cr = mCache.apply(packageName, viewRule, true);
                if (cr.oldImagePath != null) {
                    try {
                        FileUtils.delete(cr.oldImagePath);
                    } catch (Exception e) {
                        mLogger.w("write rule (json path): delete old image failed", e);
                    }
                }
                writeMsg = new WriteMessage(packageName, viewRule, null, cr.json, cr.snapshotRules,
                        null, cr.generation);
            }
            mHandle.obtainMessage(MSG_WRITE_RULE, writeMsg).sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("write rule failed", e);
            return false;
        }
    }

    /**
     * 异步更新规则 — 先应用缓存，再发送 Handler 消息持久化 + 发布。
     */
    public boolean updateRule(String packageName, RuleRecord viewRule) {
        if (!PackageNameValidator.isValid(packageName) || viewRule == null) return false;
        try {
            RuleCache.CacheResult cr = mCache.apply(packageName, viewRule, false);
            mHandle.obtainMessage(MSG_WRITE_RULE,
                    new WriteMessage(packageName, null, null, cr.json, cr.snapshotRules,
                            null, cr.generation))
                    .sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("update rule failed", e);
            return false;
        }
    }

    /**
     * 异步删除规则。
     */
    public boolean deleteRule(String packageName, RuleRecord viewRule) {
        if (!PackageNameValidator.isValid(packageName) || viewRule == null) return false;
        try {
            RuleCache.DeleteResult dr = mCache.delete(packageName, viewRule);
            if (dr == null) return false;
            mHandle.obtainMessage(MSG_DELETE_RULE,
                    new DeleteMessage(packageName, dr.json, dr.snapshotRules, dr.imagePath,
                            dr.generation))
                    .sendToTarget();
            return true;
        } catch (Exception e) {
            mLogger.w("delete rule failed", e);
            return false;
        }
    }

    /**
     * 异步删除某应用所有规则。
     */
    public boolean deleteRules(String packageName) {
        if (!PackageNameValidator.isValid(packageName)) return false;
        mLogger.d("delete rules pkg=" + packageName + " size=" + mCache.size());
        boolean removed = mCache.deleteAll(packageName);
        // Even when the cache is empty, a deferred snapshot write may still be in flight.
        // Tombstone it so the worker cannot resurrect the package after this request.
        long generation = mCache.nextGeneration();
        mStore.markDeleted(packageName, generation);
        if (removed) {
            mHandle.obtainMessage(MSG_DELETE_RULES,
                    new DeleteAllMessage(packageName, generation)).sendToTarget();
            return true;
        }
        return false;
    }

    // ===== 生命周期 =====

    /**
     * 加载所有已持久化的规则到内存。
     */
    public void loadAll() {
        loadAll(null, null);
    }

    /**
     * 加载所有已持久化的规则到内存，并在工作线程中报告结果。
     */
    public void loadAll(Runnable onSuccess, Runnable onFailure) {
        mHandle.post(() -> {
            try {
                mStore.loadAllRules();
                mStore.loadToolbarPrefs();
                mDataLoaded = true;
                int totalPackages = mCache.size();
                int totalRules = countTotalRules();
                mLogger.i("loaded " + totalPackages + " packages with "
                        + totalRules + " total rules, state=READY");
                if (onSuccess != null) onSuccess.run();
            } catch (Exception e) {
                mLogger.e("loadAll failed", e);
                mDataLoaded = true;
                if (onFailure != null) onFailure.run();
            }
        });
    }

    /** 数据是否已加载完成 */
    public boolean isDataLoaded() {
        return mDataLoaded;
    }

    /** 关闭，释放资源。 */
    public void shutdown() {
        mStore.flushPendingWrites();
        mHandle.removeCallbacksAndMessages(null);
        mWorkThread.quitSafely();
    }

    // ===== 图片/文件工具 =====

    public String saveBitmap(Bitmap bitmap, String dir) {
        return mStore.saveBitmap(bitmap, dir);
    }

    public String getAppDataDir(String packageName) throws FileNotFoundException {
        if (!PackageNameValidator.isValid(packageName)) {
            throw new FileNotFoundException("Invalid package name: " + packageName);
        }
        return mStore.getAppDataDir(packageName);
    }

    public boolean isValidImagePath(String filePath) {
        return mStore.isValidImagePath(filePath);
    }

    // ===== 工具栏偏好 =====

    public String loadToolbarHiddenItems() {
        return mStore.loadToolbarPrefs();
    }

    public void persistToolbarHiddenItems(String items) {
        mStore.persistToolbarPrefs(items);
    }

    // ===================================================================
    // Handler 消息处理
    // ===================================================================

    private boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_WRITE_RULE:
                handleWrite((WriteMessage) msg.obj);
                return true;
            case MSG_DELETE_RULE:
                handleDelete((DeleteMessage) msg.obj);
                return true;
            case MSG_DELETE_RULES:
                handleDeleteAll((DeleteAllMessage) msg.obj);
                return true;
            case MSG_DEBOUNCE_WRITE:
                mStore.handleDebouncedWrite((String) msg.obj);
                return true;
            case MSG_CLEAN_ORPHANS:
                mStore.cleanAllOrphanImages();
                return true;
        }
        return false;
    }

    private void handleWrite(WriteMessage m) {
        if (m.snapshot != null) {
            // ── 快照分支：先 I/O 保存新图，成功后再更新缓存 + 持久化 ──
            if (!mStore.isGenerationCurrent(m.packageName, m.generation)) {
                mLogger.d("drop stale snapshot write for deleted package " + m.packageName);
                return;
            }
            String oldImagePath = mCache.getOldImagePath(m.packageName, m.viewRule);

            String newImagePath;
            try {
                newImagePath = mStore.saveBitmap(m.snapshot,
                        mStore.getAppDataDir(m.packageName));
            } catch (IOException e) {
                mLogger.w("write rule: save bitmap failed — cache untouched", e);
                return;
            }
            if (newImagePath == null) {
                mLogger.w("write rule aborted: save snapshot returned null", (String) null);
                return;
            }

            if (!mStore.isGenerationCurrent(m.packageName, m.generation)) {
                FileUtils.delete(newImagePath);
                mLogger.d("drop snapshot write invalidated during image save for "
                        + m.packageName);
                return;
            }

            try {
                m.viewRule.imagePath = newImagePath;
                RuleCache.CacheResult cr = mCache.apply(m.packageName, m.viewRule, false);
                if (!mStore.isGenerationCurrent(m.packageName, m.generation)) {
                    // The delete won the package scope while the snapshot was being saved.
                    // Remove the provisional cache entry and the newly-created image.
                    mCache.delete(m.packageName, m.viewRule);
                    FileUtils.delete(newImagePath);
                    mLogger.d("drop snapshot write after package tombstone for "
                            + m.packageName);
                    return;
                }
                // Binder 即时推送
                mObserverRegistry.notifyObserverRuleChanged(m.packageName, cr.snapshotRules);
                // 持久化
                mStore.persistAsync(m.packageName, cr.json, cr.generation);
                // 清理旧图
                if (oldImagePath != null && !oldImagePath.isEmpty()) {
                    FileUtils.delete(oldImagePath);
                }
                scheduleOrphanCleanup();
            } catch (Exception e) {
                mLogger.w("write rule: persist after snapshot failed", e);
            }
        } else {
            // ── JSON 分支：缓存已更新，只持久化 + 发布 ──
            try {
                mObserverRegistry.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
                mStore.persistAsync(m.packageName, m.json, m.generation);
                scheduleOrphanCleanup();
            } catch (Exception e) {
                mLogger.w("write rule: persist failed", e);
            }
        }
    }

    private void handleDelete(DeleteMessage m) {
        try {
            mObserverRegistry.notifyObserverRuleChanged(m.packageName, m.snapshotRules);
            mStore.persistAsync(m.packageName, m.json, m.generation);
            if (m.imagePath != null && !m.imagePath.isEmpty()) {
                FileUtils.delete(m.imagePath);
            }
            scheduleOrphanCleanup();
        } catch (Exception e) {
            mLogger.w("delete rule failed", e);
        }
    }

    private void handleDeleteAll(DeleteAllMessage message) {
        String packageName = message.packageName;
        try {
            mStore.deletePackage(packageName, message.generation);
            mObserverRegistry.notifyObserverRuleChanged(packageName, new ActRules());
        } catch (Exception e) {
            mLogger.w("delete all rules failed for " + packageName, e);
        }
    }

    private void scheduleOrphanCleanup() {
        if (!mHandle.hasMessages(MSG_CLEAN_ORPHANS)) {
            mHandle.sendEmptyMessageDelayed(MSG_CLEAN_ORPHANS, ORPHAN_CLEAN_INTERVAL);
        }
    }

    private int countTotalRules() {
        int count = 0;
        AppRules all = mCache.getAllRules();
        for (ActRules actRules : all.values()) {
            for (List<RuleRecord> rules : actRules.values()) {
                if (rules != null) count += rules.size();
            }
        }
        return count;
    }

    // ===================================================================
    // RuleCache — 线程安全内存缓存
    // ===================================================================

    static final class RuleCache {
        private final AppRules mData = new AppRules();
        private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();
        private final ReentrantReadWriteLock.ReadLock mReadLock = mLock.readLock();
        private final ReentrantReadWriteLock.WriteLock mWriteLock = mLock.writeLock();
        private final Gson mGson;
        private final Logger mLogger;
        private long mGeneration; // 单调递增的 generation

        RuleCache(Gson gson, Logger logger) {
            this.mGson = gson;
            this.mLogger = logger;
        }

        synchronized long nextGeneration() {
            long now = System.currentTimeMillis();
            if (now > mGeneration) {
                mGeneration = now;
            } else {
                mGeneration++;
            }
            return mGeneration;
        }

        AppRules getAllRules() {
            mReadLock.lock();
            try {
                AppRules copy = new AppRules();
                for (Map.Entry<String, ActRules> entry : mData.entrySet()) {
                    copy.put(entry.getKey(), snapshotActRules(entry.getValue()));
                }
                return copy;
            } finally {
                mReadLock.unlock();
            }
        }

        ActRules getRules(String packageName) {
            mReadLock.lock();
            try {
                return mData.containsKey(packageName)
                        ? snapshotActRules(mData.get(packageName)) : new ActRules();
            } finally {
                mReadLock.unlock();
            }
        }

        void putAll(Map<String, ActRules> appRules) {
            mWriteLock.lock();
            try {
                mData.putAll(appRules);
            } finally {
                mWriteLock.unlock();
            }
        }

        int size() {
            mReadLock.lock();
            try {
                return mData.size();
            } finally {
                mReadLock.unlock();
            }
        }

        String getOldImagePath(String packageName, RuleRecord viewRule) {
            mReadLock.lock();
            try {
                ActRules actRules = mData.get(packageName);
                if (actRules == null) return null;
                List<RuleRecord> rules = actRules.get(viewRule.activityClass);
                if (rules == null) return null;
                int idx = findRuleIndex(rules, viewRule);
                return idx >= 0 ? rules.get(idx).imagePath : null;
            } finally {
                mReadLock.unlock();
            }
        }

        CacheResult apply(String packageName, RuleRecord viewRule, boolean captureOldImagePath) {
            mWriteLock.lock();
            try {
                ActRules actRules = mData.get(packageName);
                if (actRules == null) {
                    mData.put(packageName, actRules = new ActRules());
                }
                List<RuleRecord> viewRules = actRules.computeIfAbsent(
                        viewRule.activityClass, k -> new java.util.ArrayList<>());
                int index = findRuleIndex(viewRules, viewRule);
                String oldImagePath = null;
                if (index >= 0) {
                    if (captureOldImagePath) {
                        oldImagePath = viewRules.get(index).imagePath;
                    }
                    RuleRecord existing = viewRules.get(index);
                    if (viewRule.alias == null && existing.alias != null) {
                        viewRule.alias = existing.alias;
                    }
                    viewRules.set(index, viewRule);
                } else {
                    viewRules.add(viewRule);
                }
                String json = mGson.toJson(actRules);
                ActRules snapshotRules = snapshotActRules(actRules);
                return new CacheResult(oldImagePath, json, snapshotRules, nextGeneration());
            } finally {
                mWriteLock.unlock();
            }
        }

        DeleteResult delete(String packageName, RuleRecord viewRule) {
            mWriteLock.lock();
            try {
                ActRules actRules = mData.get(packageName);
                if (actRules == null) return null;
                List<RuleRecord> viewRules = actRules.get(viewRule.activityClass);
                if (viewRules == null) return null;
                int idx = findRuleIndex(viewRules, viewRule);
                boolean removed = idx >= 0 ? viewRules.remove(idx) != null : false;
                if (!removed) return null;
                if (viewRules.isEmpty()) {
                    actRules.remove(viewRule.activityClass);
                }
                String json = mGson.toJson(actRules);
                ActRules snapshotRules = snapshotActRules(actRules);
                if (actRules.isEmpty()) {
                    mData.remove(packageName);
                }
                return new DeleteResult(json, snapshotRules, viewRule.imagePath, nextGeneration());
            } finally {
                mWriteLock.unlock();
            }
        }

        boolean deleteAll(String packageName) {
            mWriteLock.lock();
            try {
                if (mData.containsKey(packageName)) {
                    mData.remove(packageName);
                    return true;
                }
                return false;
            } finally {
                mWriteLock.unlock();
            }
        }

        void collectReferencedImages(String packageName,
                java.util.function.BiConsumer<String, java.util.Set<String>> collector) {
            mReadLock.lock();
            try {
                java.util.Set<String> referenced = new java.util.HashSet<>();
                ActRules actRules = mData.get(packageName);
                if (actRules != null) {
                    for (List<RuleRecord> rules : actRules.values()) {
                        for (RuleRecord rule : rules) {
                            if (rule.imagePath != null && !rule.imagePath.isEmpty())
                                referenced.add(rule.imagePath);
                            if (rule.modImagePath != null && !rule.modImagePath.isEmpty())
                                referenced.add(rule.modImagePath);
                        }
                    }
                }
                collector.accept(packageName, referenced);
            } finally {
                mReadLock.unlock();
            }
        }

        private static int findRuleIndex(List<RuleRecord> rules,
                com.kaisar.xposed.godmode.engine.rule.MatchFields target) {
            for (int i = 0; i < rules.size(); i++) {
                if (rules.get(i).isSameViewAs(target)) return i;
            }
            return -1;
        }

        ActRules snapshotActRules(ActRules source) {
            if (source == null) return new ActRules();
            ActRules copy = new ActRules();
            for (Map.Entry<String, List<RuleRecord>> entry : source.entrySet()) {
                if (entry.getValue() == null) continue;
                List<RuleRecord> clonedList = new java.util.ArrayList<>(entry.getValue().size());
                for (RuleRecord r : entry.getValue()) {
                    if (r != null) clonedList.add(r.clone());
                }
                copy.put(entry.getKey(), clonedList);
            }
            return copy;
        }

        // ---- 内部 DTO ----

        static final class CacheResult {
            final String oldImagePath;
            final String json;
            final ActRules snapshotRules;
            final long generation;

            CacheResult(String oldImagePath, String json, ActRules snapshotRules, long generation) {
                this.oldImagePath = oldImagePath;
                this.json = json;
                this.snapshotRules = snapshotRules;
                this.generation = generation;
            }
        }

        static final class DeleteResult {
            final String json;
            final ActRules snapshotRules;
            final String imagePath;
            final long generation;

            DeleteResult(String json, ActRules snapshotRules, String imagePath, long generation) {
                this.json = json;
                this.snapshotRules = snapshotRules;
                this.imagePath = imagePath;
                this.generation = generation;
            }
        }
    }

    // ===================================================================
    // RuleStore — 文件持久化
    // ===================================================================

    static final class RuleStore {
        private static final String BASE_DIR = DATA_DIR;
        static final String RULE_FILE_SUFFIX = ".rule";
        static final String IMAGE_FILE_SUFFIX = ".webp";
        static final String TOOLBAR_PREFS_FILE = "toolbar_prefs.json";
        private static final long DEBOUNCE_DELAY_MS = 300L;

        private final Gson mGson;
        private final Logger mLogger;
        private final RuleCache mCache;
        private final Handler mHandle;
        private final Map<String, PendingWrite> mPendingWrites = new HashMap<>();
        /** Latest package tombstone. Writes from an older cache generation are stale. */
        private final Map<String, Long> mDeletedGenerations = new HashMap<>();

        RuleStore(Gson gson, Logger logger, RuleCache cache, Handler handler) {
            this.mGson = gson;
            this.mLogger = logger;
            this.mCache = cache;
            this.mHandle = handler;
        }

        void loadAllRules() throws IOException {
            File dataDir = new File(getBaseDir());
            File[] packageDirs = dataDir.listFiles(File::isDirectory);
            if (packageDirs != null) {
                HashMap<String, ActRules> appRules = new HashMap<>();
                for (File packageDir : packageDirs) {
                    if (TOOLBAR_PREFS_FILE.equals(packageDir.getName())) continue;
                    try {
                        String packageName = packageDir.getName();
                        String appRuleFile = getAppRuleFilePath(packageName);
                        String json = com.kaisar.xposed.godmode.engine.util.FileUtils.readTextFile(appRuleFile, 0, null);
                        ActRules rules = mGson.fromJson(json, ActRules.class);
                        Preconditions.checkNotNull(rules, "rules is null");
                        rules.entrySet().removeIf(e ->
                                e.getValue() == null || e.getValue().isEmpty());
                        if (rules.isEmpty()) {
                            FileUtils.delete(packageDir);
                            continue;
                        }
                        appRules.put(packageName, rules);
                    } catch (FileNotFoundException ignored) {
                        // 没有规则文件，跳过
                    } catch (IOException e) {
                        mLogger.w("load rule fail", e);
                    } catch (NullPointerException | JsonSyntaxException e) {
                        mLogger.e("load rule error for " + packageDir.getName(), e);
                        FileUtils.delete(packageDir);
                    }
                }
                mCache.putAll(appRules);
            }
        }

        void persistAsync(String packageName, String json, long generation) {
            synchronized (mPendingWrites) {
                long deletedGeneration = deletedGenerationLocked(packageName);
                if (!isWriteCurrent(generation, deletedGeneration)) {
                    mLogger.d("drop stale persistence for deleted package " + packageName
                            + ", generation=" + generation + ", tombstone=" + deletedGeneration);
                    return;
                }
                mPendingWrites.put(packageName, new PendingWrite(json, generation));
            }
            mHandle.removeMessages(MSG_DEBOUNCE_WRITE, packageName);
            mHandle.sendMessageDelayed(
                    mHandle.obtainMessage(MSG_DEBOUNCE_WRITE, packageName),
                    DEBOUNCE_DELAY_MS);
        }

        private void persistNow(String packageName, String json, long generation) {
            synchronized (mPendingWrites) {
                if (!isWriteCurrent(generation, deletedGenerationLocked(packageName))) {
                    mLogger.d("drop stale persistence for deleted package " + packageName);
                    return;
                }
            }
            try {
                File appDataDir = new File(getBaseDir(), packageName);
                if (!appDataDir.exists() && !appDataDir.mkdirs()) {
                    mLogger.w("persistAsync: cannot create dir for " + packageName);
                    return;
                }
                FileUtils.setPermissions(appDataDir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
                File ruleFile = new File(appDataDir, packageName + RULE_FILE_SUFFIX);
                File tmpFile = new File(appDataDir, packageName + RULE_FILE_SUFFIX + ".tmp");
                FileUtils.stringToFile(tmpFile, json);
                if (!tmpFile.renameTo(ruleFile)) {
                    if (tmpFile.exists() && !tmpFile.delete()) {
                        mLogger.w("Failed to delete tmp file: " + tmpFile);
                    }
                    mLogger.w("persistAsync: atomic rename failed for " + packageName);
                    return;
                }
                FileUtils.setPermissions(ruleFile, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            } catch (IOException e) {
                mLogger.w("persistAsync failed for " + packageName, e);
            }
        }
        void markDeleted(String packageName, long generation) {
            synchronized (mPendingWrites) {
                long current = deletedGenerationLocked(packageName);
                if (generation > current) mDeletedGenerations.put(packageName, generation);
                mPendingWrites.remove(packageName);
            }
            mHandle.removeMessages(MSG_DEBOUNCE_WRITE, packageName);
        }

        void deletePackage(String packageName, long generation) {
            try {
                File packageDir = new File(getBaseDir(), packageName);
                if (packageDir.exists()) FileUtils.delete(packageDir);
            } catch (FileNotFoundException e) {
                mLogger.w("delete package: base dir not found", e);
            }
        }

        private long deletedGenerationLocked(String packageName) {
            Long generation = mDeletedGenerations.get(packageName);
            return generation != null ? generation : Long.MIN_VALUE;
        }

        static boolean isWriteCurrent(long generation, long deletedGeneration) {
            return generation > deletedGeneration;
        }

        boolean isGenerationCurrent(String packageName, long generation) {
            synchronized (mPendingWrites) {
                return isWriteCurrent(generation, deletedGenerationLocked(packageName));
            }
        }

        void handleDebouncedWrite(String packageName) {
            PendingWrite pending;
            synchronized (mPendingWrites) {
                pending = mPendingWrites.get(packageName);
                if (pending == null) return;
                mPendingWrites.remove(packageName);
                if (!isWriteCurrent(pending.generation, deletedGenerationLocked(packageName))) {
                    mLogger.d("drop stale debounced write for deleted package " + packageName);
                    return;
                }
            }
            persistNow(packageName, pending.json, pending.generation);
        }

        void flushPendingWrites() {
            Map<String, PendingWrite> pending;
            synchronized (mPendingWrites) {
                pending = new HashMap<>(mPendingWrites);
                mPendingWrites.clear();
            }
            for (Map.Entry<String, PendingWrite> entry : pending.entrySet()) {
                synchronized (mPendingWrites) {
                    if (!isWriteCurrent(entry.getValue().generation,
                            deletedGenerationLocked(entry.getKey()))) {
                        continue;
                    }
                }
                persistNow(entry.getKey(), entry.getValue().json, entry.getValue().generation);
            }
        }

        String saveBitmap(Bitmap bitmap, String dir) {
            try {
                Bitmap bitmapToSave = bitmap;
                if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                    bitmapToSave = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    new Canvas(bitmapToSave).drawBitmap(bitmap, 0, 0, null);
                }
                File file = new File(dir, System.currentTimeMillis() + IMAGE_FILE_SUFFIX);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    if (bitmapToSave.compress(Bitmap.CompressFormat.WEBP, 80, out)) {
                        FileUtils.setPermissions(file, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
                        return file.getAbsolutePath();
                    }
                    throw new FileNotFoundException("bitmap can't compress to " + file);
                } finally {
                    if (bitmapToSave != bitmap) {
                        CommonUtils.recycleNullableBitmap(bitmapToSave);
                    }
                }
            } catch (IOException e) {
                mLogger.w("save bitmap fail", e);
                return null;
            }
        }

        void cleanAllOrphanImages() {
            try {
                File dataDir = new File(getBaseDir());
                File[] packageDirs = dataDir.listFiles(File::isDirectory);
                if (packageDirs == null) return;
                for (File packageDir : packageDirs) {
                    if (TOOLBAR_PREFS_FILE.equals(packageDir.getName())) continue;
                    String packageName = packageDir.getName();
                    File[] imageFiles = packageDir.listFiles(
                            (dir, name) -> name.endsWith(IMAGE_FILE_SUFFIX));
                    if (imageFiles == null || imageFiles.length == 0) continue;
                    mCache.collectReferencedImages(packageName, (pkg, referenced) ->
                            Arrays.stream(imageFiles)
                                    .filter(f -> !referenced.contains(f.getAbsolutePath()))
                                    .forEach(FileUtils::delete));
                }
            } catch (FileNotFoundException e) {
                mLogger.w("orphan cleanup: base dir not found", e);
            }
        }

        String loadToolbarPrefs() {
            try {
                File prefsFile = new File(getBaseDir(), TOOLBAR_PREFS_FILE);
                if (prefsFile.exists()) {
                    String items = FileUtils.readTextFile(prefsFile, 0, null);
                    return items != null ? items : "";
                }
            } catch (Exception e) {
                mLogger.w("load toolbar prefs failed", e);
            }
            return "";
        }

        void persistToolbarPrefs(String items) {
            try {
                File prefsFile = new File(getBaseDir(), TOOLBAR_PREFS_FILE);
                FileUtils.stringToFile(prefsFile, items);
                FileUtils.setPermissions(prefsFile, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            } catch (Exception e) {
                mLogger.w("persist toolbar prefs failed", e);
            }
        }

        String getBaseDir() throws FileNotFoundException {
            File dir = new File(BASE_DIR);
            if (dir.exists() || dir.mkdirs()) {
                FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
                return dir.getAbsolutePath();
            }
            throw new FileNotFoundException("Cannot create base dir: " + BASE_DIR);
        }

        String getAppDataDir(String packageName) throws FileNotFoundException {
            File dir = new File(getBaseDir(), packageName);
            if (dir.exists() || dir.mkdirs()) {
                FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
                return dir.getAbsolutePath();
            }
            throw new FileNotFoundException("Cannot create app data dir: " + dir);
        }

        String getAppRuleFilePath(String packageName) throws IOException {
            File file = new File(getAppDataDir(packageName), packageName + RULE_FILE_SUFFIX);
            if (file.exists()) {
                return file.getAbsolutePath();
            }
            throw new FileNotFoundException("规则文件不存在: " + file);
        }

        boolean isValidImagePath(String filePath) {
            try {
                String base = new File(getBaseDir()).getCanonicalPath();
                String target = new File(filePath).getCanonicalPath();
                return (target.equals(base) || target.startsWith(base + File.separator))
                        && filePath.endsWith(IMAGE_FILE_SUFFIX);
            } catch (IOException e) {
                return false;
            }
        }
    }

    // ===================================================================
    // 内部消息 DTO
    // ===================================================================

    static final class WriteMessage {
        final String packageName;
        final RuleRecord viewRule;
        final Bitmap snapshot;
        final String json;
        final ActRules snapshotRules;
        final String imagePath;
        final long generation;

        WriteMessage(String packageName, RuleRecord viewRule, Bitmap snapshot,
                     String json, ActRules snapshotRules, String imagePath, long generation) {
            this.packageName = packageName;
            this.viewRule = viewRule;
            this.snapshot = snapshot;
            this.json = json;
            this.snapshotRules = snapshotRules;
            this.imagePath = imagePath;
            this.generation = generation;
        }
    }

    static final class DeleteMessage {
        final String packageName;
        final String json;
        final ActRules snapshotRules;
        final String imagePath;
        final long generation;

        DeleteMessage(String packageName, String json, ActRules snapshotRules,
                      String imagePath, long generation) {
            this.packageName = packageName;
            this.json = json;
            this.snapshotRules = snapshotRules;
            this.imagePath = imagePath;
            this.generation = generation;
        }
    }

    static final class DeleteAllMessage {
        final String packageName;
        final long generation;

        DeleteAllMessage(String packageName, long generation) {
            this.packageName = packageName;
            this.generation = generation;
        }
    }

    private static final class PendingWrite {
        final String json;
        final long generation;

        PendingWrite(String json, long generation) {
            this.json = json;
            this.generation = generation;
        }
    }
}
