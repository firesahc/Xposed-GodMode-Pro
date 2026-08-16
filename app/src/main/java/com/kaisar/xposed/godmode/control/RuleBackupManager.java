package com.kaisar.xposed.godmode.control;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

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
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
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

    public static void backupRules(Uri toUri, String packageName, List<RuleRecord> viewRules) throws BackupException {
        if (!PackageNameValidator.isValid(packageName) || viewRules == null) {
            throw new BackupException("Invalid backup arguments");
        }
        Logger.i(TAG, "[Backup] backupRules: start, package=" + packageName + ", ruleCount=" + viewRules.size());
        ArrayList<String> backupFilePathList = new ArrayList<>();
        ArrayList<RuleRecord> backupRuleRecordList = new ArrayList<>(viewRules.size());
        ImageEntryRegistry imageEntries = new ImageEntryRegistry();
        File backupDir = createOperationDirectory(
                GodModeApplication.getApplication().getCacheDir(), "backup");
        try {
            prepareFreshDirectory(backupDir);
            Logger.d(TAG, "[Backup] backupRules: temp dir created, package=" + packageName);
                for (RuleRecord viewRule : viewRules) {
                    if (viewRule == null || !packageName.equals(viewRule.packageName)) {
                        throw new IOException("Rule package does not match backup package");
                    }
                    RuleRecord viewRuleCopy = prepareBackupRecord(viewRule);
                    try {
                        String entryName = copyImageToBackup(backupDir,
                                viewRule.imagePath, "", imageEntries,
                                backupFilePathList);
                        viewRuleCopy.imagePath = entryName != null ? entryName : "";
                    } catch (IOException e) {
                        viewRuleCopy.imagePath = "";
                        Logger.w(TAG, "[Backup] backupRules: skip image for " + viewRule.viewClass + ", failed to copy", e);
                    }
                    if (viewRule.isModifyRule() && !TextUtils.isEmpty(viewRule.modImagePath)) {
                        if (viewRule.modImagePath.equals(viewRule.imagePath)) {
                            viewRuleCopy.modImagePath = viewRuleCopy.imagePath;
                        } else {
                            try {
                                String entryName = copyImageToBackup(backupDir,
                                        viewRule.modImagePath, "mod_", imageEntries,
                                        backupFilePathList);
                                viewRuleCopy.modImagePath = entryName != null ? entryName : "";
                            } catch (IOException e) {
                                viewRuleCopy.modImagePath = "";
                                Logger.w(TAG, "[Backup] backupRules: skip mod image for " + viewRule.viewClass + ", failed to copy", e);
                            }
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
                Logger.d(TAG, "[Backup] backupRules: manifest written, fileCount=" + backupFilePathList.size());
                try (OutputStream out = GodModeApplication.getApplication().getContentResolver().openOutputStream(toUri)) {
                    ZipUtils.compress(out, backupFilePathList.toArray(new String[0]));
                }
                Logger.i(TAG, "[Backup] backupRules: success, package=" + packageName + ", rules=" + backupRuleRecordList.size());
        } catch (IOException e) {
            Logger.e(TAG, "[Backup] backupRules: failed, package=" + packageName, e);
            throw new BackupException(e);
        } finally {
            cleanupTempDirectory("backupRules", backupDir);
        }
    }

    public static int restoreRules(Uri fromUri) throws RestoreException {
        Logger.i(TAG, "[Backup] restoreRules: start, uri=" + fromUri);
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
                Logger.d(TAG, "[Backup] restoreRules: manifest parsed, ruleCount=" + jsonArray.size());
                for (int i = 0; i < jsonArray.size(); i++) {
                    String ruleJson = jsonArray.get(i).toString();
                    RuleRecord viewRule = gson.fromJson(ruleJson, RuleRecord.class);
                    if (viewRule == null || !manifestPackageName.equals(viewRule.packageName)) {
                        throw new RestoreException("Rule package does not match manifest");
                    }

                    // 先保存主图，获取持久化路径
                    Bitmap bitmap = null;
                    if (!TextUtils.isEmpty(viewRule.imagePath)) {
                        File imageFile = resolveRestoredFile(restoreDir, viewRule.imagePath);
                        try {
                            bitmap = imageFile != null
                                    ? SafeBitmapDecoder.decodeFile(imageFile.getPath()) : null;
                        } catch (RuntimeException e) {
                            Logger.w(TAG, "[Backup] restoreRules: main image decode failed", e);
                        }
                        if (bitmap != null) {
                            String savedPath = null;
                            try {
                                savedPath = RuleServiceClient.getDefault()
                                        .saveImageFile(viewRule.packageName, bitmap);
                            } catch (Exception e) {
                                Logger.w(TAG, "[Backup] restoreRules: main image save failed", e);
                            }
                            viewRule.imagePath = savedPath != null ? savedPath : "";
                        } else {
                            // Never persist a ZIP-relative path as if it were a durable image path.
                            viewRule.imagePath = "";
                        }
                    }

                    // 有修改图时一并保存，组装完整规则
                    if (viewRule.isModifyRule() && !TextUtils.isEmpty(viewRule.modImagePath)) {
                        File modFile = resolveRestoredFile(restoreDir, viewRule.modImagePath);
                        Bitmap modBitmap = null;
                        try {
                            modBitmap = modFile != null
                                    ? SafeBitmapDecoder.decodeFile(modFile.getPath()) : null;
                        } catch (RuntimeException e) {
                            Logger.w(TAG, "[Backup] restoreRules: mod image decode failed", e);
                        }
                        if (modBitmap != null) {
                            String savedModPath = null;
                            try {
                                savedModPath = RuleServiceClient.getDefault()
                                        .saveImageFile(viewRule.packageName, modBitmap);
                            } catch (Exception e) {
                                Logger.w(TAG, "[Backup] restoreRules: mod image save failed", e);
                            }
                            viewRule.modImagePath = savedModPath != null ? savedModPath : "";
                            recycleNullableBitmap(modBitmap);
                        } else {
                            // A failed replacement image must not leave a dangling ZIP entry name.
                            viewRule.modImagePath = "";
                        }
                    }

                    // 一次调用写入完整规则（包含主图和修改图路径），消除异步竞态
                    RuleServiceClient.getDefault().writeRule(viewRule.packageName, viewRule, bitmap);

                    recycleNullableBitmap(bitmap);
                }
                Logger.i(TAG, "[Backup] restoreRules: success, ruleCount=" + jsonArray.size());
                return jsonArray.size();
        } catch (IOException e) {
            Logger.e(TAG, "[Backup] restoreRules: failed", e);
            throw new RestoreException(e);
        } catch (Exception e) {
            Logger.e(TAG, "[Backup] restoreRules: failed, malformed data", e);
            throw new RestoreException(e);
        } finally {
            cleanupTempDirectory("restoreRules", restoreDir);
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
        if (!input.isModifyRule()) copy.modImagePath = "";
        return copy;
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
