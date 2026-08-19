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
import java.io.FileInputStream;
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
        ArrayList<String> backupFilePathList = new ArrayList<>();
        ArrayList<RuleRecord> backupRuleRecordList = new ArrayList<>(viewRules.size());
        ImageEntryRegistry imageEntries = new ImageEntryRegistry();
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
            for (RuleRecord viewRule : selectedRules) {
                if (viewRule == null || !packageName.equals(viewRule.packageName)) {
                    throw new IOException("Rule package does not match backup package");
                }
                RuleRecord viewRuleCopy = prepareBackupRecord(viewRule);
                if (!TextUtils.isEmpty(viewRule.imagePath)) {
                    String entryName = copyImageToBackup(backupDir,
                            viewRule.imagePath, "", imageEntries,
                            backupFilePathList);
                    if (entryName == null) {
                        throw new IOException("required main image is unavailable");
                    }
                    viewRuleCopy.imagePath = entryName;
                }
                if (viewRule.isModifyRule() && !TextUtils.isEmpty(viewRule.getModImagePath())) {
                    if (viewRule.getModImagePath().equals(viewRule.imagePath)) {
                        viewRuleCopy = viewRuleCopy.withModifyImagePath(viewRuleCopy.imagePath);
                    } else {
                        String entryName = copyImageToBackup(backupDir,
                                viewRule.getModImagePath(), "mod_", imageEntries,
                                backupFilePathList);
                        if (entryName == null) {
                            throw new IOException("required modified image is unavailable");
                        }
                        viewRuleCopy = viewRuleCopy.withModifyImagePath(entryName);
                    }
                }
                backupRuleRecordList.add(viewRuleCopy);
            }
            File manifestFile = new File(backupDir, MANIFEST_FILE);
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("version", VERSION);
            jsonObject.addProperty("packageName", packageName);
            Gson gson = new GsonBuilder().create();
            JsonElement jsonElement = gson.toJsonTree(backupRuleRecordList);
            jsonObject.add("rules", jsonElement);
            FileUtils.stringToFile(manifestFile, jsonObject.toString());
            backupFilePathList.add(manifestFile.getPath());
            Logger.d(TAG, "backupRules manifest written fileCount=" + backupFilePathList.size());
            try (OutputStream out = GodModeApplication.getApplication().getContentResolver().openOutputStream(toUri)) {
                ZipUtils.compress(out, backupFilePathList.toArray(new String[0]));
            }
            Logger.i(TAG, "backupRules success package=" + packageName
                    + " rules=" + backupRuleRecordList.size());
        } catch (IOException | RuntimeException e) {
            Logger.e(TAG, "backupRules failed package=" + packageName, e);
            throw new BackupException(e);
        } finally {
            if (backupDir != null) cleanupTempDirectory("backupRules", backupDir);
            serviceClient.endBackup();
        }
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
                String json = FileUtils.readTextFile(manifestFile, 0, null);
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
            List<String> backupFilePathList) throws IOException {
        if (TextUtils.isEmpty(imagePath)) return null;
        String existingEntry = imageEntries.find(imagePath);
        if (existingEntry != null) return existingEntry;
        try (ParcelFileDescriptor pfd = RuleServiceClient.getDefault()
                .openImageFileDescriptor(imagePath)) {
            if (pfd == null) return null;
            String sourceName = new File(imagePath).getName();
            if (sourceName.isEmpty()) sourceName = "image.webp";
            String entryName = imageEntries.reserve(preferredPrefix + sourceName);
            File file = new File(backupDir, entryName);
            try (InputStream in = new FileInputStream(pfd.getFileDescriptor());
                 OutputStream out = new FileOutputStream(file)) {
                if (!FileUtils.copy(in, out)) {
                    throw new IOException("Failed to copy image: " + imagePath);
                }
            }
            backupFilePathList.add(file.getPath());
            imageEntries.record(imagePath, entryName);
            return entryName;
        }
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
}
