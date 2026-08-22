package com.kaisar.xposed.godmode.control;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;
import static com.kaisar.xposed.godmode.engine.util.GmConstants.MAX_IMAGE_FILE_SIZE_BYTES;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kaisar.xposed.godmode.GodModeApplication;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.ZipUtils;
import com.kaisar.xposed.godmode.engine.applier.SafeBitmapDecoder;
import com.kaisar.xposed.godmode.engine.rule.RuleSlotKey;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 规则备份/恢复管理器 — 将规则及其关联图片导出为 ZIP 压缩包，或从 ZIP 导入恢复。
 * <p>
 * 从 {@code util/BackupUtils} 迁入 control/ 层，职责不变。
 * 依赖 {@link RuleServiceClient} 跨进程读写规则和图片，
 * 依赖 {@link RuleRecord} 序列化/反序列化规则数据。
 */
public final class RuleBackupManager {

    private static final String TAG = "RuleBackupManager";
    private static final int VERSION = 1;
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int MAX_BACKUP_RULES = 512;
    private static final int MAX_BACKUP_ENTRIES = 1 + 2 * MAX_BACKUP_RULES;
    private static final long MAX_BACKUP_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L;
    /** manifest.json 读入上限 — 512 条规则的紧凑 JSON 约 0.4MB，留 10 倍余量。 */
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;

    /** 生产图片流来源 — 经服务端描述符打开规则图片；返回拥有型流，关闭即释放 pfd。 */
    private static final BackupImageOpener SERVICE_IMAGE_OPENER =
            RuleBackupManager::openServiceImage;

    private RuleBackupManager() {
    }

    public static class BackupException extends Exception {
        public BackupException(String message) { super(message); }
        public BackupException(Throwable cause) { super(cause); }
    }

    public static class RestoreException extends Exception {
        public RestoreException(String message) { super(message); }
        public RestoreException(Throwable cause) { super(cause); }
    }

    public static final class RestoreReport {
        public final int total;
        public final int committed;
        public final List<EntryResult> entries;

        RestoreReport(int total, int committed, List<EntryResult> entries) {
            this.total = total;
            this.committed = committed;
            this.entries = java.util.Collections.unmodifiableList(
                    new ArrayList<>(entries));
        }

        public int failed() {
            return total - committed;
        }
    }

    public static final class EntryResult {
        public enum Status { COMMITTED, REJECTED, WRITE_FAILED }

        public final int index;
        public final Status status;
        public final String message;

        EntryResult(int index, Status status, String message) {
            this.index = index;
            this.status = status;
            this.message = message;
        }
    }

    public static void backupRules(Uri toUri, String packageName, List<RuleRecord> viewRules) throws BackupException {
        if (!PackageNameValidator.isValid(packageName) || viewRules == null) {
            throw new BackupException("Invalid backup arguments");
        }
        RuleServiceClient serviceClient = RuleServiceClient.getDefault();
        if (!serviceClient.beginBackup()) {
            throw new BackupException("Rule service is unavailable or another operation is active");
        }
        Logger.i(TAG, "backupRules start package=" + packageName + " ruleCount=" + viewRules.size());
        File backupDir = null;
        try {
            backupDir = createOperationDirectory(
                    GodModeApplication.getApplication().getCacheDir(), "backup");
            ActRules authoritative = serviceClient.getRules(packageName);
            if (authoritative == null) {
                throw new BackupException("Unable to read authoritative rule snapshot");
            }
            List<RuleRecord> currentRules = flatten(authoritative);
            List<RuleRecord> selectedRules = selectCurrentRules(viewRules, currentRules);
            if (selectedRules.size() != viewRules.size()) {
                throw new BackupException("Selected rules changed before backup");
            }
            prepareFreshDirectory(backupDir);
            Logger.d(TAG, "backupRules temp directory created package=" + packageName);
            List<String> backupFilePathList;
            try (OutputStream out = GodModeApplication.getApplication()
                    .getContentResolver().openOutputStream(toUri)) {
                backupFilePathList = writeArchive(out, backupDir, packageName, selectedRules);
            }
            Logger.d(TAG, "backupRules archive assembled fileCount=" + backupFilePathList.size());
            Logger.i(TAG, "backupRules success package=" + packageName
                    + " rules=" + viewRules.size());
        } catch (IOException | RuntimeException e) {
            Logger.e(TAG, "backupRules failed package=" + packageName, e);
            throw new BackupException(e);
        } finally {
            if (backupDir != null) cleanupTempDirectory("backupRules", backupDir);
            serviceClient.endBackup();
        }
    }

    /**
     * ZIP V1 归档组装内核 — 纯净的核心循环，不含任何 Android 环境依赖
     * （图片来源经 {@link BackupImageOpener} 注入），可在 JVM 单测中锁定布局合同。
     * <p>
     * 职责：遍历 {@code rules} 并复制关联图片到 {@code workDir}——主图沿用源文件名，
     * mod 图追加 {@code mod_} 前缀；同源路径复用同一归档条目；重名冲突由
     * {@link ImageEntryRegistry#reserve} 追加 {@code _N} 后缀，空源名兜底
     * {@code image.webp}。随后将各副本记录的 {@code imagePath}/{@code getModImagePath()}
     * 替换为归档条目名，序列化 manifest {@code {version: VERSION, packageName,
     * rules:[...]}} 写入 {@code workDir} 下的 {@value #MANIFEST_FILE}；最后经
     * {@link ZipUtils#compress} 将全部文件以平铺条目（仅取 {@link File#getName()}，
     * 无目录层级）写入 {@code out}。
     * 编排职责（临时目录生命周期、服务端快照选取、备份租约、清理与日志）仍归
     * {@link #backupRules} 所有。
     *
     * @param out         归档目标输出流；压缩完成后随 ZipOutputStream 一并关闭
     * @param workDir     归档暂存目录，必须已存在且可写
     * @param packageName 备份归属包名，用于逐条校验规则归属并写入 manifest
     * @param rules       待归档的规则集（应为服务端权威快照的选中子集）
     * @return 实际写入的文件路径列表（图片文件 + manifest），顺序即压缩顺序
     * @throws IOException 任一规则包名与 {@code packageName} 不匹配、必需图片不可用
     *                     或发生 I/O 失败
     */
    static List<String> writeArchive(OutputStream out, File workDir, String packageName,
                                     List<RuleRecord> rules) throws IOException {
        return writeArchive(out, workDir, packageName, rules, SERVICE_IMAGE_OPENER);
    }

    /** 内核重载 — 注入图片流来源使归档组装保持 JVM 可测；生产路径固定走服务端实现。 */
    static List<String> writeArchive(OutputStream out, File workDir, String packageName,
                                     List<RuleRecord> rules,
                                     BackupImageOpener imageOpener) throws IOException {
        if (rules.size() > MAX_BACKUP_RULES) {
            // 与 restoreRules 的 manifest 数组上限对称，防止未来调用方绕过编排层直连内核
            throw new IOException("Backup exceeds rule limit: " + rules.size());
        }
        ArrayList<String> backupFilePathList = new ArrayList<>();
        ArrayList<RuleRecord> backupRuleRecordList = new ArrayList<>(rules.size());
        ImageEntryRegistry imageEntries = new ImageEntryRegistry();
        for (RuleRecord viewRule : rules) {
            if (viewRule == null || !packageName.equals(viewRule.packageName)) {
                throw new IOException("Rule package does not match backup package");
            }
            RuleRecord viewRuleCopy = prepareBackupRecord(viewRule);
            if (!isEmptyText(viewRule.imagePath)) {
                String entryName = copyImageToBackup(workDir,
                        viewRule.imagePath, "", imageEntries, backupFilePathList,
                        imageOpener);
                if (entryName == null) {
                    throw new IOException("required main image is unavailable");
                }
                viewRuleCopy.imagePath = entryName;
            }
            if (viewRule.isModifyRule() && !isEmptyText(viewRule.getModImagePath())) {
                if (viewRule.getModImagePath().equals(viewRule.imagePath)) {
                    viewRuleCopy = viewRuleCopy.withModifyImagePath(viewRuleCopy.imagePath);
                } else {
                    String entryName = copyImageToBackup(workDir,
                            viewRule.getModImagePath(), "mod_", imageEntries,
                            backupFilePathList, imageOpener);
                    if (entryName == null) {
                        throw new IOException("required modified image is unavailable");
                    }
                    viewRuleCopy = viewRuleCopy.withModifyImagePath(entryName);
                }
            }
            backupRuleRecordList.add(viewRuleCopy);
        }
        File manifestFile = new File(workDir, MANIFEST_FILE);
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("version", VERSION);
        jsonObject.addProperty("packageName", packageName);
        Gson gson = new GsonBuilder().create();
        JsonElement jsonElement = gson.toJsonTree(backupRuleRecordList);
        jsonObject.add("rules", jsonElement);
        FileUtils.stringToFile(manifestFile, jsonObject.toString());
        backupFilePathList.add(manifestFile.getPath());
        ZipUtils.compress(out, backupFilePathList.toArray(new String[0]));
        return backupFilePathList;
    }

    public static RestoreReport restoreRules(Uri fromUri) throws RestoreException {
        Logger.i(TAG, "restoreRules start source=selected_uri");
        File restoreDir = createOperationDirectory(
                GodModeApplication.getApplication().getCacheDir(), "restore");
        try {
            prepareFreshDirectory(restoreDir);
                try (InputStream in = GodModeApplication.getApplication().getContentResolver().openInputStream(fromUri)) {
                    ZipUtils.uncompress(in, restoreDir.getPath(),
                            MAX_BACKUP_UNCOMPRESSED_BYTES, MAX_BACKUP_ENTRIES);
                }
                File manifestFile = new File(restoreDir, MANIFEST_FILE);
                if (!manifestFile.exists()) throw new RestoreException("Miss manifest.json file.");
                // 上限读入：超限截断必然导致 JSON 解析失败而被下方 catch 拒绝，
                // 防止恶意超大 manifest 将整个文件堆入内存（zip 总量上限内的单文件仍可接近 256MB）。
                String json = FileUtils.readTextFile(manifestFile, MAX_MANIFEST_BYTES, null);
                Gson gson = new GsonBuilder().create();
                JsonObject jsonObject = gson.fromJson(json, JsonObject.class);
                if (jsonObject == null || !jsonObject.has("version")
                        || jsonObject.get("version").getAsInt() != VERSION) {
                    throw new RestoreException("Unsupported backup version");
                }
                if (!jsonObject.has("packageName")
                        || !PackageNameValidator.isValid(
                        jsonObject.get("packageName").getAsString())) {
                    throw new RestoreException("Invalid backup package name");
                }
                String manifestPackageName = jsonObject.get("packageName").getAsString();
                JsonArray jsonArray = jsonObject.getAsJsonArray("rules");
                if (jsonArray == null) throw new RestoreException("Missing rules array");
                if (jsonArray.size() > MAX_BACKUP_RULES) {
                    throw new RestoreException("Backup exceeds rule limit: " + jsonArray.size());
                }
                Logger.d(TAG, "restoreRules manifest parsed ruleCount=" + jsonArray.size());
                if (!RuleServiceClient.getDefault().beginRestore()) {
                    throw new RestoreException("Rule service is unavailable or another restore is active");
                }
                int committed = 0;
                ArrayList<EntryResult> results = new ArrayList<>(jsonArray.size());
                for (int i = 0; i < jsonArray.size(); i++) {
                    Bitmap bitmap = null;
                    Bitmap modBitmap = null;
                    try {
                        String ruleJson = jsonArray.get(i).toString();
                        RuleRecord viewRule = gson.fromJson(ruleJson, RuleRecord.class);
                        if (viewRule == null || !manifestPackageName.equals(viewRule.packageName)) {
                            Logger.w(TAG, "restoreRules entry rejected index=" + i
                                    + " reason=package_mismatch");
                            results.add(new EntryResult(i, EntryResult.Status.REJECTED,
                                    "rule package does not match manifest"));
                            continue;
                        }

                        if (!TextUtils.isEmpty(viewRule.imagePath)) {
                            File imageFile = resolveRestoredFile(restoreDir, viewRule.imagePath);
                            bitmap = decodeRequiredImage(imageFile);
                            if (bitmap == null) {
                                Logger.w(TAG, "restoreRules entry rejected index=" + i
                                        + " reason=main_image_invalid");
                                results.add(new EntryResult(i, EntryResult.Status.REJECTED,
                                        "main image is missing or invalid"));
                                continue;
                            }
                        }

                        if (viewRule.isModifyRule() && !TextUtils.isEmpty(viewRule.getModImagePath())) {
                            File modFile = resolveRestoredFile(restoreDir, viewRule.getModImagePath());
                            modBitmap = decodeRequiredImage(modFile);
                            if (modBitmap == null) {
                                Logger.w(TAG, "restoreRules entry rejected index=" + i
                                        + " reason=modified_image_invalid");
                                results.add(new EntryResult(i, EntryResult.Status.REJECTED,
                                        "modified image is missing or invalid"));
                                continue;
                            }
                        }

                        boolean accepted = RuleServiceClient.getDefault().writeRule(
                                viewRule.packageName, viewRule, bitmap, modBitmap);
                        if (accepted) {
                            committed++;
                            results.add(new EntryResult(i, EntryResult.Status.COMMITTED, "committed"));
                        } else {
                            Logger.w(TAG, "restoreRules entry write failed index=" + i);
                            results.add(new EntryResult(i, EntryResult.Status.WRITE_FAILED,
                                    "rule or asset persistence failed"));
                        }
                    } catch (Exception e) {
                        Logger.w(TAG, "restoreRules entry rejected index=" + i, e);
                        results.add(new EntryResult(i, EntryResult.Status.REJECTED,
                                e.getMessage() == null ? "invalid rule entry" : e.getMessage()));
                    } finally {
                        recycleNullableBitmap(bitmap);
                        recycleNullableBitmap(modBitmap);
                    }
                }
                Logger.i(TAG, "restoreRules complete committed=" + committed
                        + " failed=" + (jsonArray.size() - committed));
                return new RestoreReport(jsonArray.size(), committed, results);
        } catch (IOException e) {
            Logger.e(TAG, "restoreRules failed", e);
            throw new RestoreException(e);
        } catch (Exception e) {
            Logger.e(TAG, "restoreRules failed malformed_data", e);
            throw new RestoreException(e);
        } finally {
            RuleServiceClient.getDefault().endRestore();
            cleanupTempDirectory("restoreRules", restoreDir);
        }
    }

    private static List<RuleRecord> flatten(ActRules rules) {
        List<RuleRecord> result = new ArrayList<>();
        for (List<RuleRecord> entries : rules.values()) {
            if (entries != null) result.addAll(entries);
        }
        return result;
    }

    static List<RuleRecord> selectCurrentRules(List<RuleRecord> requested,
                                               List<RuleRecord> current) {
        List<RuleRecord> selected = new ArrayList<>(requested.size());
        for (RuleRecord wanted : requested) {
            if (wanted == null) continue;
            RuleSlotKey wantedSlot = wanted.slotKey(wanted.packageName);
            for (int i = current.size() - 1; i >= 0; i--) {
                RuleRecord candidate = current.get(i);
                if (candidate != null
                        && candidate.slotKey(candidate.packageName)
                        .equals(wantedSlot)) {
                    selected.add(candidate);
                    break;
                }
            }
        }
        return selected;
    }

    private static Bitmap decodeRequiredImage(File file) {
        if (file == null || !file.isFile()
                || file.length() <= 0L || file.length() > MAX_IMAGE_FILE_SIZE_BYTES) {
            return null;
        }
        try {
            return SafeBitmapDecoder.decodeFile(file.getPath());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void prepareFreshDirectory(File dir) throws IOException {
        try {
            if (dir.exists() && !FileUtils.delete(dir.getPath())) {
                throw new IOException("Delete temp directory failed: " + dir);
            }
            if (!dir.mkdirs()) {
                throw new IOException("Create temp directory failed: " + dir);
            }
        } catch (SecurityException e) {
            throw new IOException("Prepare temp directory failed: " + dir, e);
        }
    }

    static File createOperationDirectory(File cacheDir, String operation) {
        return new File(new File(cacheDir, operation), UUID.randomUUID().toString());
    }

    /** Creates the export-owned record without mutating the caller's rule. */
    static RuleRecord prepareBackupRecord(RuleRecord input) {
        RuleRecord copy = input.clone();
        return input.isModifyRule() ? copy : copy.withModifyImagePath("");
    }

    private static String copyImageToBackup(
            File backupDir, String imagePath, String preferredPrefix,
            ImageEntryRegistry imageEntries,
            List<String> backupFilePathList,
            BackupImageOpener imageOpener) throws IOException {
        if (isEmptyText(imagePath)) return null;
        String existingEntry = imageEntries.find(imagePath);
        if (existingEntry != null) return existingEntry;
        try (InputStream in = imageOpener.open(imagePath)) {
            if (in == null) return null;
            String sourceName = new File(imagePath).getName();
            if (sourceName.isEmpty()) sourceName = "image.webp";
            String entryName = imageEntries.reserve(preferredPrefix + sourceName);
            File file = new File(backupDir, entryName);
            try (OutputStream out = new FileOutputStream(file)) {
                if (!FileUtils.copy(in, out)) {
                    throw new IOException("Failed to copy image: " + imagePath);
                }
            }
            backupFilePathList.add(file.getPath());
            imageEntries.record(imagePath, entryName);
            return entryName;
        }
    }

    /** 生产实现 — 打开服务端图片描述符并包装为拥有型流；{@code null} 表示来源不可用。 */
    private static InputStream openServiceImage(String imagePath) {
        ParcelFileDescriptor pfd = RuleServiceClient.getDefault()
                .openImageFileDescriptor(imagePath);
        return pfd == null ? null : new ParcelFileDescriptor.AutoCloseInputStream(pfd);
    }

    /** 规则图片输入流来源 — 生产路径绑定服务端描述符，内核测试注入本地文件实现。 */
    interface BackupImageOpener {

        /**
         * 打开指定规则图片的输入流；由调用方负责关闭返回的流。
         *
         * @return 图片输入流，{@code null} 表示图片来源不可用
         */
        InputStream open(String imagePath) throws IOException;
    }

    static final class ImageEntryRegistry {
        private final Map<String, String> entryByPath = new HashMap<>();
        private final Set<String> usedNames = new HashSet<>();

        String find(String sourcePath) {
            return entryByPath.get(sourcePath);
        }

        void record(String sourcePath, String entryName) {
            entryByPath.put(sourcePath, entryName);
        }

        String reserve(String preferredName) {
            String safeName = preferredName == null || preferredName.isEmpty()
                    ? "image.webp" : preferredName;
            if (usedNames.add(safeName)) return safeName;
            int dot = safeName.lastIndexOf('.');
            String baseName = dot > 0 ? safeName.substring(0, dot) : safeName;
            String extension = dot > 0 ? safeName.substring(dot) : "";
            for (int index = 1; ; index++) {
                String candidate = baseName + "_" + index + extension;
                if (usedNames.add(candidate)) return candidate;
            }
        }
    }

    static File resolveRestoredFile(File restoreDir, String entryName)
            throws IOException {
        if (entryName == null || entryName.isEmpty()) return null;
        File base = restoreDir.getCanonicalFile();
        File entry = new File(entryName);
        if (entry.isAbsolute()) return null;
        File file = new File(base, entryName).getCanonicalFile();
        String basePath = base.getPath();
        String filePath = file.getPath();
        if (!filePath.startsWith(basePath + File.separator) || !file.isFile()) {
            return null;
        }
        return file;
    }

    private static void cleanupTempDirectory(String operation, File dir) {
        try {
            if (dir.exists() && !FileUtils.delete(dir.getPath())) {
                Logger.w(TAG, operation + ": cleanup temp directory failed: " + dir);
            }
        } catch (RuntimeException e) {
            Logger.w(TAG, operation + ": cleanup temp directory failed: " + dir, e);
        }
    }

    /** 归档内核专用空串判断，语义与 {@code TextUtils.isEmpty} 一致以保持 JVM 纯净。 */
    private static boolean isEmptyText(String value) {
        return value == null || value.length() == 0;
    }
}
