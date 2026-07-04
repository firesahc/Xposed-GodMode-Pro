package com.kaisar.xposed.godmode.data;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.kaisar.xposed.godmode.engine.rule.RuleSnapshot;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * 规则快照存储 — 提供独立于 Binder 的规则访问通道。
 * <p>
 * 存储结构：
 * <pre>
 * /data/misc/godmode/snapshots/
 *   ├── {packageName}.json       # 最新快照
 *   ├── {packageName}.json.tmp   # 写入中的临时文件
 *   └── {packageName}.gen        # generation 号（防回退）
 * </pre>
 * <p>
 * 写入流程：tmp → write → fsync → rename → update gen
 * 读取流程：读文件 → fromJson → 校验 schemaVersion + generation
 * <p>
 * 所有文件操作复用项目现有基础工具类，
 * 确保文件权限一致（S_IRWXU | S_IRWXG | S_IRWXO）。
 * <p>
 * 【关键约束】此类不依赖 Binder 或任何 IPC 相关类。
 */
public final class RuleSnapshotStore {

    private static final String TAG = "RuleSnapshotStore";

    private static volatile RuleSnapshotStore sInstance;

    private static final Type RULE_LIST_TYPE = new TypeToken<List<RuleRecord>>() {}.getType();

    /** 快照目录 */
    private final File mSnapshotDir;
    private final Gson mGson = new Gson();

    // ===== 单例 =====

    private RuleSnapshotStore() {
        this.mSnapshotDir = new File(DataBusConstants.SNAPSHOT_DIR);
    }

    public static RuleSnapshotStore getDefault() {
        if (sInstance == null) {
            synchronized (RuleSnapshotStore.class) {
                if (sInstance == null) {
                    sInstance = new RuleSnapshotStore();
                }
            }
        }
        return sInstance;
    }

    /**
     * 获取或创建快照目录（仅供非单例模式使用）。
     *
     * @param snapshotDir 自定义快照目录
     */
    RuleSnapshotStore(File snapshotDir) {
        this.mSnapshotDir = snapshotDir;
    }

    // ===== 写入 =====

    /**
     * 原子写入快照：tmp → write → fsync → rename → update gen。
     * <p>
     * 如果 generation 回退（小于等于当前记录值），拒绝写入并返回 false。
     *
     * @param packageName 包名
     * @param snapshot    规则快照
     * @return true 如果成功写入（generation 递增）；false 如果被拒绝（generation 回退）
     * @throws IOException I/O 错误
     */
    public boolean writeSnapshot(String packageName, RuleSnapshot snapshot) throws IOException {
        if (packageName == null || snapshot == null) {
            Logger.w(TAG, "writeSnapshot skipped: null packageName or snapshot");
            return false;
        }

        // 1. 确保目录存在
        ensureDir();

        // 2. 检查 generation 是否大于当前值
        if (!validateGeneration(packageName, snapshot.generation)) {
            Logger.w(TAG, "writeSnapshot rejected: generation rollback detected for "
                    + packageName + " (gen=" + snapshot.generation + ")");
            return false;
        }

        // 3. 写入 .tmp 文件
        File tmpFile = new File(mSnapshotDir, packageName + DataBusConstants.TMP_FILE_SUFFIX);
        String json = mGson.toJson(snapshot);
        FileUtils.stringToFile(tmpFile, json);
        // fsync：确保数据写入磁盘
        syncFile(tmpFile);

        // 4. 原子替换
        File target = new File(mSnapshotDir, packageName + DataBusConstants.SNAPSHOT_FILE_SUFFIX);
        if (!tmpFile.renameTo(target)) {
            // rename 失败，尝试清理 tmp
            if (tmpFile.exists() && !tmpFile.delete()) {
                Logger.w(TAG, "failed to delete tmp file: " + tmpFile);
            }
            throw new IOException("atomic rename failed: " + tmpFile + " -> " + target);
        }

        // 5. 设置权限（跨进程可访问）
        FileUtils.setPermissions(target, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);

        // 6. 更新 generation 文件（原子写入）
        writeGeneration(packageName, snapshot.generation);

        Logger.d(TAG, "snapshot written for " + packageName
                + " gen=" + snapshot.generation);
        return true;
    }

    // ===== 读取 =====

    /**
     * 读取指定包的最后有效快照。
     * <p>
     * 不依赖 Binder，直接读文件。如果快照损坏或校验失败，返回 null。
     *
     * @param packageName 包名
     * @return 规则快照，不存在或损坏返回 null
     */
    public RuleSnapshot readLatest(String packageName) {
        if (packageName == null) return null;

        File file = new File(mSnapshotDir, packageName + DataBusConstants.SNAPSHOT_FILE_SUFFIX);
        if (!file.exists() || !file.isFile()) return null;

        try {
            String json = FileUtils.readTextFile(file.getAbsolutePath(), 0, null);
            if (json == null || json.isEmpty()) return null;

            RuleSnapshot snapshot = RuleSnapshot.fromJson(json);
            if (snapshot == null) return null;

            // 校验
            return snapshot.validate() ? snapshot : null;
        } catch (Exception e) {
            Logger.w(TAG, "readLatest failed for " + packageName, e);
            return null;
        }
    }

    /**
     * 读取指定包的最后有效快照，并强类型还原为 {@link ActRules}。
     * <p>
     * {@link RuleSnapshot} 位于 engine 模块，无法声明 app 层的 {@link RuleRecord} 类型。
     * runtime 降级路径必须使用此方法，避免把 Gson/JSON 的 Map 结构误当成 RuleRecord。
     */
    public ActRules readLatestRules(String packageName) {
        if (packageName == null) return null;

        File file = new File(mSnapshotDir, packageName + DataBusConstants.SNAPSHOT_FILE_SUFFIX);
        if (!file.exists() || !file.isFile()) return null;

        try {
            String json = FileUtils.readTextFile(file.getAbsolutePath(), 0, null);
            if (json == null || json.isEmpty()) return null;

            JsonObject root = mGson.fromJson(json, JsonObject.class);
            if (root == null) return null;

            int schemaVersion = getInt(root, "schemaVersion", 0);
            long generation = getLong(root, "generation", 0L);
            String snapshotPackage = getString(root, "packageName");
            if (schemaVersion != RuleSnapshot.CURRENT_VERSION
                    || generation <= 0
                    || !packageName.equals(snapshotPackage)) {
                Logger.w(TAG, "readLatestRules rejected invalid snapshot for " + packageName);
                return null;
            }

            JsonObject payload = root.getAsJsonObject("payload");
            ActRules rules = new ActRules();
            if (payload == null) return rules;

            for (Map.Entry<String, JsonElement> entry : payload.entrySet()) {
                List<RuleRecord> ruleList = mGson.fromJson(entry.getValue(), RULE_LIST_TYPE);
                if (ruleList != null && !ruleList.isEmpty()) {
                    rules.put(entry.getKey(), ruleList);
                }
            }
            return rules;
        } catch (Exception e) {
            Logger.w(TAG, "readLatestRules failed for " + packageName, e);
            return null;
        }
    }

    // ===== Generation 管理 =====

    /**
     * 校验指定包的 generation 是否单调递增。
     *
     * @param packageName   包名
     * @param expectedGeneration 期望的 generation 值
     * @return true 如果 generation 大于当前记录值（或记录不存在）
     */
    boolean validateGeneration(String packageName, long expectedGeneration) {
        long current = readGeneration(packageName);
        return expectedGeneration > current;
    }

    /**
     * 读取指定包的当前 generation 值。如果文件不存在返回 0。
     */
    long readGeneration(String packageName) {
        File genFile = new File(mSnapshotDir, packageName + DataBusConstants.GEN_FILE_SUFFIX);
        if (!genFile.exists()) return 0L;
        try {
            String content = FileUtils.readTextFile(genFile.getAbsolutePath(), 0, null);
            if (content == null || content.isEmpty()) return 0L;
            return Long.parseLong(content.trim());
        } catch (Exception e) {
            Logger.w(TAG, "readGeneration failed for " + packageName, e);
            return 0L;
        }
    }

    /**
     * 原子写入 generation 值。
     */
    void writeGeneration(String packageName, long generation) throws IOException {
        File genFile = new File(mSnapshotDir, packageName + DataBusConstants.GEN_FILE_SUFFIX);
        FileUtils.stringToFile(genFile, Long.toString(generation));
        syncFile(genFile);
        FileUtils.setPermissions(genFile, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
    }

    // ===== 校验 =====

    /**
     * 校验指定包的快照是否有效（schemaVersion 和 generation）。
     *
     * @param packageName 包名
     * @return true 如果快照存在且有效
     */
    public boolean validate(String packageName) {
        RuleSnapshot snapshot = readLatest(packageName);
        return snapshot != null && snapshot.schemaVersion == RuleSnapshot.CURRENT_VERSION;
    }

    // ===== 维护 =====

    /**
     * 删除指定包的所有快照文件。
     *
     * @param packageName 包名
     */
    public void deleteSnapshots(String packageName) {
        deleteFile(packageName + DataBusConstants.SNAPSHOT_FILE_SUFFIX);
        deleteFile(packageName + DataBusConstants.TMP_FILE_SUFFIX);
        deleteFile(packageName + DataBusConstants.GEN_FILE_SUFFIX);
    }

    private void deleteFile(String fileName) {
        File file = new File(mSnapshotDir, fileName);
        if (file.exists() && !file.delete()) {
            Logger.w(TAG, "failed to delete: " + file);
        }
    }

    // ===== 目录与 fsync =====

    /**
     * 对指定文件执行 fsync，确保数据写入磁盘。
     */
    private static void syncFile(File file) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.getFD().sync();
        }
    }

    private void ensureDir() throws IOException {
        if (!mSnapshotDir.exists() && !mSnapshotDir.mkdirs()) {
            throw new IOException("Failed to create snapshot dir: " + mSnapshotDir);
        }
        FileUtils.setPermissions(mSnapshotDir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() ? value.getAsInt() : fallback;
    }

    private static long getLong(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() ? value.getAsLong() : fallback;
    }

    private static String getString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() ? value.getAsString() : null;
    }
}
