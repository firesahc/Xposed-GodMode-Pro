package com.kaisar.xposed.godmode.control;

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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * 写操作在统一互斥区内同步完成：候选快照先持久化，成功后才提交缓存并发布。
 * <p>
 * 规则发布走 Binder 即时推送（{@link ObserverRegistry#notifyObserverRuleChanged}），
 * 不再依赖文件快照链路。
 */
public final class RuleRepository {
    private static final int PRIVATE_DIR_MODE = S_IRWXU;
    private static final int PRIVATE_FILE_MODE = 00600;

    private static final String TAG = "RuleRepository";

    // ===== 消息码 =====
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
    private final Object mSnapshotMutationLock = new Object();

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

    /** Latest generation whose snapshot has been committed to the in-memory authority. */
    public long getGeneration() {
        synchronized (mSnapshotMutationLock) {
            return mCache.currentGeneration();
        }
    }

    public RepositorySnapshot<AppRules> getAllRulesSnapshot() {
        synchronized (mSnapshotMutationLock) {
            return new RepositorySnapshot<>(mCache.getAllRules(), mCache.currentGeneration());
        }
    }

    public RepositorySnapshot<ActRules> getRulesSnapshot(String packageName) {
        synchronized (mSnapshotMutationLock) {
            return new RepositorySnapshot<>(mCache.getRules(packageName),
                    mCache.currentGeneration());
        }
    }

    public static final class RepositorySnapshot<T> {
        public final T value;
        public final long generation;

        RepositorySnapshot(T value, long generation) {
            this.value = value;
            this.generation = generation;
        }
    }

    /** 查询某规则在缓存中的旧 imagePath */
    public String getOldImagePath(String packageName, RuleRecord viewRule) {
        return mCache.getOldImagePath(packageName, viewRule);
    }

    // ===== 写操作 =====

    /**
     * 结果状态 for synchronous mutations.  A mutation is visible to readers and observers only
     * after its serialized candidate has been committed successfully.
     */
    public enum MutationStatus {
        COMMITTED,
        NO_CHANGE,
        WRITE_FAILED,
        REJECTED
    }

    /** Structured result used by the Binder service while the legacy boolean methods remain. */
    public static final class MutationResult {
        public final MutationStatus status;
        public final String packageName;
        public final long generation;
        public final ActRules rules;
        public final String error;

        private MutationResult(MutationStatus status, String packageName, long generation,
                               ActRules rules, String error) {
            this.status = status;
            this.packageName = packageName;
            this.generation = generation;
            this.rules = rules;
            this.error = error;
        }

        static MutationResult committed(String packageName, long generation, ActRules rules) {
            return new MutationResult(MutationStatus.COMMITTED, packageName, generation, rules, null);
        }

        static MutationResult noChange(String packageName, long generation, ActRules rules) {
            return new MutationResult(MutationStatus.NO_CHANGE, packageName, generation, rules, null);
        }

        static MutationResult failed(String packageName, String error) {
            return new MutationResult(MutationStatus.WRITE_FAILED, packageName, 0L, null, error);
        }

        static MutationResult rejected(String packageName, String error) {
            return new MutationResult(MutationStatus.REJECTED, packageName, 0L, null, error);
        }

        public boolean isCommitted() {
            return status == MutationStatus.COMMITTED;
        }
    }

    static RuleRecord copyForPackage(String packageName, RuleRecord input) {
        if (packageName == null || input == null) return null;
        return input.clone().withPackageName(packageName);
    }

    static boolean isPendingSnapshotFor(String packageName, RuleRecord pending,
            String targetPackage, RuleRecord target) {
        return packageName != null && packageName.equals(targetPackage)
                && pending != null && target != null
                && pending.slotKey(packageName).equals(target.slotKey(targetPackage));
    }

    /**
     * Persist-first write entry point.  The optional bitmaps become rule-owned image paths.
     * The append flag is retained for callers; the existing slot matcher decides whether this
     * operation inserts or replaces a record, preserving the historical rule semantics.
     */
    public MutationResult mutateWrite(String packageName, RuleRecord viewRule,
                                      Bitmap snapshot, Bitmap modifiedSnapshot,
                                      boolean append) {
        if (!PackageNameValidator.isValid(packageName) || viewRule == null) {
            return MutationResult.rejected(packageName, "invalid package or rule");
        }
        synchronized (mSnapshotMutationLock) {
            List<String> newImages = new ArrayList<>();
            File packageDir = new File(DATA_DIR, packageName);
            boolean packageDirExisted = packageDir.exists();
            try {
                RuleRecord ownedRule = copyForPackage(packageName, viewRule);
                String dir = mStore.getAppDataDir(packageName);
                if (snapshot != null) {
                    String path = mStore.saveBitmap(snapshot, dir);
                    if (path == null) {
                        cleanupNewImages(newImages, packageDir, packageDirExisted);
                        return MutationResult.failed(packageName, "main image write failed");
                    }
                    newImages.add(path);
                    ownedRule = ownedRule.withImagePath(path);
                }
                if (modifiedSnapshot != null) {
                    String path = mStore.saveBitmap(modifiedSnapshot, dir);
                    if (path == null) {
                        cleanupNewImages(newImages, packageDir, packageDirExisted);
                        return MutationResult.failed(packageName, "modified image write failed");
                    }
                    newImages.add(path);
                    ownedRule = ownedRule.withModifyImagePath(path);
                }

                RuleCache.CacheResult candidate = mCache.prepareApply(
                        packageName, ownedRule, append);
                if (!mStore.persistNow(packageName, candidate.json, candidate.generation)) {
                    cleanupNewImages(newImages, packageDir, packageDirExisted);
                    return MutationResult.failed(packageName, "rule file write failed");
                }
                mCache.commitApply(packageName, candidate);
                mObserverRegistry.notifyObserverRuleChanged(packageName, candidate.generation);
                deleteReplacedImages(candidate.oldImagePaths, candidate.snapshotRules);
                scheduleOrphanCleanup();
                return MutationResult.committed(packageName, candidate.generation,
                        candidate.snapshotRules);
            } catch (Exception e) {
                cleanupNewImages(newImages, packageDir, packageDirExisted);
                mLogger.w("persist-first write failed", e);
                return MutationResult.failed(packageName, e.getMessage());
            }
        }
    }

    /** Persist-first deletion of one rule. */
    public MutationResult mutateDelete(String packageName, RuleRecord viewRule) {
        if (!PackageNameValidator.isValid(packageName) || viewRule == null) {
            return MutationResult.rejected(packageName, "invalid package or rule");
        }
        synchronized (mSnapshotMutationLock) {
            try {
                RuleCache.DeleteResult candidate = mCache.prepareDelete(packageName, viewRule);
                if (candidate == null) {
                    return MutationResult.noChange(packageName, mCache.currentGeneration(),
                            mCache.getRules(packageName));
                }
                if (!mStore.persistNow(packageName, candidate.json, candidate.generation)) {
                    return MutationResult.failed(packageName, "rule file write failed");
                }
                mCache.commitDelete(packageName, candidate);
                mObserverRegistry.notifyObserverRuleChanged(packageName, candidate.generation);
                deleteFiles(candidate.removedImagePaths);
                scheduleOrphanCleanup();
                return MutationResult.committed(packageName, candidate.generation,
                        candidate.snapshotRules);
            } catch (Exception e) {
                mLogger.w("persist-first delete failed", e);
                return MutationResult.failed(packageName, e.getMessage());
            }
        }
    }

    /** Persist-first deletion of a package.  An empty JSON snapshot is the durable tombstone. */
    public MutationResult mutateDeleteAll(String packageName) {
        if (!PackageNameValidator.isValid(packageName)) {
            return MutationResult.rejected(packageName, "invalid package");
        }
        synchronized (mSnapshotMutationLock) {
            try {
                RuleCache.DeleteAllResult candidate = mCache.prepareDeleteAll(packageName);
                if (candidate == null) {
                    return MutationResult.noChange(packageName, mCache.currentGeneration(),
                            new ActRules());
                }
                if (!mStore.persistNow(packageName, "{}", candidate.generation)) {
                    return MutationResult.failed(packageName, "rule file write failed");
                }
                mStore.markDeleted(packageName, candidate.generation);
                mCache.commitDeleteAll(packageName, candidate);
                mObserverRegistry.notifyObserverRuleChanged(packageName, candidate.generation);
                deleteFiles(candidate.removedImagePaths);
                scheduleOrphanCleanup();
                return MutationResult.committed(packageName, candidate.generation, new ActRules());
            } catch (Exception e) {
                mLogger.w("persist-first delete all failed", e);
                return MutationResult.failed(packageName, e.getMessage());
            }
        }
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

    public boolean hasToolbarHiddenItems() {
        return mStore.hasToolbarPrefs();
    }

    public boolean persistToolbarHiddenItems(String items) {
        return mStore.persistToolbarPrefs(items);
    }

    // ===================================================================
    // Handler 消息处理
    // ===================================================================

    private boolean handleMessage(Message msg) {
        if (msg.what == MSG_CLEAN_ORPHANS) {
            mStore.cleanAllOrphanImages();
            return true;
        }
        return false;
    }

    private static void deleteFiles(List<String> paths) {
        if (paths == null) return;
        for (String path : paths) {
            if (path != null && !path.isEmpty()) FileUtils.delete(path);
        }
    }

    private static void cleanupNewImages(List<String> paths, File packageDir,
                                         boolean packageDirExisted) {
        deleteFiles(paths);
        if (!packageDirExisted && packageDir.exists()) {
            File[] children = packageDir.listFiles();
            if (children == null || children.length == 0) FileUtils.delete(packageDir);
        }
    }

    private static void deleteReplacedImages(List<String> oldPaths, ActRules candidateRules) {
        if (oldPaths == null || oldPaths.isEmpty()) return;
        java.util.Set<String> retained = new java.util.HashSet<>();
        if (candidateRules != null) {
            for (List<RuleRecord> rules : candidateRules.values()) {
                if (rules == null) continue;
                for (RuleRecord rule : rules) {
                    if (rule == null) continue;
                    if (rule.imagePath != null) retained.add(rule.imagePath);
                    if (rule.getModImagePath() != null) retained.add(rule.getModImagePath());
                }
            }
        }
        for (String path : oldPaths) {
            if (path != null && !path.isEmpty() && !retained.contains(path)) {
                FileUtils.delete(path);
            }
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

        synchronized long proposedGeneration() {
            long now = System.currentTimeMillis();
            return now > mGeneration ? now : mGeneration + 1L;
        }

        synchronized void commitGeneration(long generation) {
            if (generation > mGeneration) mGeneration = generation;
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
                List<RuleRecord> rules = actRules.get(viewRule.getActivityClass());
                if (rules == null) return null;
                int idx = findRuleIndex(rules, viewRule);
                return idx >= 0 ? rules.get(idx).imagePath : null;
            } finally {
                mReadLock.unlock();
            }
        }

        DeleteResult prepareDelete(String packageName, RuleRecord viewRule) {
            mReadLock.lock();
            try {
                ActRules candidate = snapshotActRules(mData.get(packageName));
                if (candidate.isEmpty()) return null;
                List<RuleRecord> viewRules = candidate.get(viewRule.getActivityClass());
                if (viewRules == null) return null;
                int idx = findRuleIndex(viewRules, viewRule);
                RuleRecord removedRule = idx >= 0 ? viewRules.remove(idx) : null;
                if (removedRule == null) return null;
                if (viewRules.isEmpty()) candidate.remove(viewRule.getActivityClass());
                return new DeleteResult(mGson.toJson(candidate), snapshotActRules(candidate),
                        imagePathsOf(removedRule), candidate, proposedGeneration());
            } finally {
                mReadLock.unlock();
            }
        }

        void commitDelete(String packageName, DeleteResult candidate) {
            mWriteLock.lock();
            try {
                if (candidate.candidateRules.isEmpty()) mData.remove(packageName);
                else mData.put(packageName, snapshotActRules(candidate.candidateRules));
                commitGeneration(candidate.generation);
            } finally {
                mWriteLock.unlock();
            }
        }

        DeleteAllResult prepareDeleteAll(String packageName) {
            mReadLock.lock();
            try {
                ActRules current = mData.get(packageName);
                if (current == null) return null;
                List<String> removed = new ArrayList<>();
                for (List<RuleRecord> rules : current.values()) {
                    if (rules == null) continue;
                    for (RuleRecord rule : rules) removed.addAll(imagePathsOf(rule));
                }
                return new DeleteAllResult(removed, proposedGeneration());
            } finally {
                mReadLock.unlock();
            }
        }

        void commitDeleteAll(String packageName, DeleteAllResult candidate) {
            mWriteLock.lock();
            try {
                mData.remove(packageName);
                commitGeneration(candidate.generation);
            } finally {
                mWriteLock.unlock();
            }
        }

        private static List<String> imagePathsOf(RuleRecord rule) {
            List<String> paths = new ArrayList<>(2);
            if (rule != null) {
                if (rule.imagePath != null && !rule.imagePath.isEmpty()) paths.add(rule.imagePath);
                if (rule.getModImagePath() != null && !rule.getModImagePath().isEmpty()) {
                    paths.add(rule.getModImagePath());
                }
            }
            return paths;
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
                            if (rule.getModImagePath() != null && !rule.getModImagePath().isEmpty())
                                referenced.add(rule.getModImagePath());
                        }
                    }
                }
                collector.accept(packageName, referenced);
            } finally {
                mReadLock.unlock();
            }
        }

        private static int findRuleIndex(List<RuleRecord> rules, RuleRecord target) {
            int match = -1;
            for (int i = 0; i < rules.size(); i++) {
                RuleRecord candidate = rules.get(i);
                if (candidate != null && target != null
                        && candidate.slotKey(candidate.packageName)
                        .equals(target.slotKey(target.packageName))) {
                    // Historical snapshots can contain duplicate slots. Preserve their order and
                    // keep the last writer as the target for legacy replace/delete semantics.
                    match = i;
                }
            }
            return match;
        }

        long currentGeneration() {
            synchronized (this) {
                return mGeneration;
            }
        }

        /** Build a candidate without changing the published cache. */
        CacheResult prepareApply(String packageName, RuleRecord viewRule,
                                 boolean captureOldImagePath) {
            mReadLock.lock();
            try {
                ActRules candidate = snapshotActRules(mData.get(packageName));
                List<RuleRecord> viewRules = candidate.computeIfAbsent(
                        viewRule.getActivityClass(), k -> new java.util.ArrayList<>());
                int index = findRuleIndex(viewRules, viewRule);
                List<String> oldImagePaths = new ArrayList<>();
                if (index >= 0) {
                    RuleRecord existing = viewRules.get(index);
                    if (captureOldImagePath && existing != null) {
                        oldImagePaths.addAll(imagePathsOf(existing));
                    }
                    if (viewRule.alias == null && existing != null && existing.alias != null) {
                        viewRule = viewRule.withAlias(existing.alias);
                    }
                    viewRules.set(index, viewRule);
                } else {
                    viewRules.add(viewRule);
                }
                return new CacheResult(oldImagePaths, candidate, mGson.toJson(candidate),
                        snapshotActRules(candidate), proposedGeneration());
            } finally {
                mReadLock.unlock();
            }
        }

        void commitApply(String packageName, CacheResult candidate) {
            mWriteLock.lock();
            try {
                mData.put(packageName, snapshotActRules(candidate.candidateRules));
                commitGeneration(candidate.generation);
            } finally {
                mWriteLock.unlock();
            }
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
            final List<String> oldImagePaths;
            final ActRules candidateRules;
            final String json;
            final ActRules snapshotRules;
            final long generation;

            CacheResult(List<String> oldImagePaths, ActRules candidateRules, String json,
                        ActRules snapshotRules, long generation) {
                this.oldImagePaths = oldImagePaths;
                this.candidateRules = candidateRules;
                this.json = json;
                this.snapshotRules = snapshotRules;
                this.generation = generation;
            }
        }

        static final class DeleteResult {
            final String json;
            final ActRules snapshotRules;
            final List<String> removedImagePaths;
            final ActRules candidateRules;
            final long generation;

            DeleteResult(String json, ActRules snapshotRules, List<String> removedImagePaths,
                         ActRules candidateRules, long generation) {
                this.json = json;
                this.snapshotRules = snapshotRules;
                this.removedImagePaths = removedImagePaths;
                this.candidateRules = candidateRules;
                this.generation = generation;
            }
        }

        static final class DeleteAllResult {
            final List<String> removedImagePaths;
            final long generation;

            DeleteAllResult(List<String> removedImagePaths, long generation) {
                this.removedImagePaths = removedImagePaths;
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
        private static final String QUARANTINE_PREFIX = ".quarantine-";
        private final Gson mGson;
        private final Logger mLogger;
        private final RuleCache mCache;
        private final Handler mHandle;
        private final Object mGenerationLock = new Object();
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
                    if (TOOLBAR_PREFS_FILE.equals(packageDir.getName())
                            || packageDir.getName().startsWith(QUARANTINE_PREFIX)) continue;
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
                        quarantine(packageDir);
                    }
                }
                mCache.putAll(appRules);
            }
        }

        private void quarantine(File packageDir) {
            File target = new File(packageDir.getParentFile(),
                    QUARANTINE_PREFIX + packageDir.getName() + "-" + UUID.randomUUID());
            if (!packageDir.renameTo(target)) {
                mLogger.w("cannot quarantine corrupt rule directory: " + packageDir);
            } else {
                mLogger.w("quarantined corrupt rule directory: " + target);
            }
        }

        private boolean persistNow(String packageName, String json, long generation) {
            synchronized (mGenerationLock) {
                if (!isWriteCurrent(generation, deletedGenerationLocked(packageName))) {
                    mLogger.d("drop stale persistence for deleted package " + packageName);
                    return false;
                }
            }
            File tmpFile = null;
            try {
                File appDataDir = new File(getBaseDir(), packageName);
                if (!appDataDir.exists() && !appDataDir.mkdirs()) {
                mLogger.w("persist: cannot create dir for " + packageName);
                    return false;
                }
                FileUtils.setPermissions(appDataDir, PRIVATE_DIR_MODE, -1, -1);
                File ruleFile = new File(appDataDir, packageName + RULE_FILE_SUFFIX);
                tmpFile = new File(appDataDir, packageName + RULE_FILE_SUFFIX + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.getFD().sync();
                }
                if (!tmpFile.renameTo(ruleFile)) {
                    if (tmpFile.exists() && !tmpFile.delete()) {
                        mLogger.w("Failed to delete tmp file: " + tmpFile);
                    }
                    mLogger.w("persist: atomic rename failed for " + packageName);
                    return false;
                }
                FileUtils.setPermissions(ruleFile, PRIVATE_FILE_MODE, -1, -1);
                return true;
            } catch (Exception e) {
                if (tmpFile != null && tmpFile.exists()) FileUtils.delete(tmpFile);
                mLogger.w("persist failed for " + packageName, e);
                return false;
            }
        }
        void markDeleted(String packageName, long generation) {
            synchronized (mGenerationLock) {
                long current = deletedGenerationLocked(packageName);
                if (generation > current) mDeletedGenerations.put(packageName, generation);
            }
        }

        private long deletedGenerationLocked(String packageName) {
            Long generation = mDeletedGenerations.get(packageName);
            return generation != null ? generation : Long.MIN_VALUE;
        }

        static boolean isWriteCurrent(long generation, long deletedGeneration) {
            return generation > deletedGeneration;
        }

        String saveBitmap(Bitmap bitmap, String dir) {
            File file = null;
            try {
                Bitmap bitmapToSave = bitmap;
                if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                    bitmapToSave = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    new Canvas(bitmapToSave).drawBitmap(bitmap, 0, 0, null);
                }
                // Include a monotonic component so two images committed in one millisecond
                // cannot overwrite one another or accidentally delete an older asset.
                file = new File(dir, System.currentTimeMillis() + "-" + System.nanoTime()
                        + IMAGE_FILE_SUFFIX);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    if (bitmapToSave.compress(Bitmap.CompressFormat.WEBP, 80, out)) {
                        out.flush();
                        out.getFD().sync();
                        FileUtils.setPermissions(file, PRIVATE_FILE_MODE, -1, -1);
                        return file.getAbsolutePath();
                    }
                    throw new FileNotFoundException("bitmap can't compress to " + file);
                } finally {
                    if (bitmapToSave != bitmap) {
                        CommonUtils.recycleNullableBitmap(bitmapToSave);
                    }
                }
            } catch (IOException e) {
                if (file != null && file.exists()) FileUtils.delete(file);
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

        boolean hasToolbarPrefs() {
            try {
                return new File(getBaseDir(), TOOLBAR_PREFS_FILE).isFile();
            } catch (Exception e) {
                mLogger.w("check toolbar prefs failed", e);
                return false;
            }
        }

        boolean persistToolbarPrefs(String items) {
            try {
                File prefsFile = new File(getBaseDir(), TOOLBAR_PREFS_FILE);
                FileUtils.stringToFile(prefsFile, items);
                FileUtils.setPermissions(prefsFile, PRIVATE_FILE_MODE, -1, -1);
                return true;
            } catch (Exception e) {
                mLogger.w("persist toolbar prefs failed", e);
                return false;
            }
        }

        String getBaseDir() throws FileNotFoundException {
            File dir = new File(BASE_DIR);
            if (dir.exists() || dir.mkdirs()) {
                FileUtils.setPermissions(dir, PRIVATE_DIR_MODE, -1, -1);
                return dir.getAbsolutePath();
            }
            throw new FileNotFoundException("Cannot create base dir: " + BASE_DIR);
        }

        String getAppDataDir(String packageName) throws FileNotFoundException {
            File dir = new File(getBaseDir(), packageName);
            if (dir.exists() || dir.mkdirs()) {
                FileUtils.setPermissions(dir, PRIVATE_DIR_MODE, -1, -1);
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

}
