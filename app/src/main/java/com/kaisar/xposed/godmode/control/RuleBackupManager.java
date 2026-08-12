package com.kaisar.xposed.godmode.control;

import static com.kaisar.xposed.godmode.engine.util.CommonUtils.recycleNullableBitmap;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
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
        Logger.i(TAG, "[Backup] backupRules: start, package=" + packageName + ", ruleCount=" + viewRules.size());
        ArrayList<String> backupFilePathList = new ArrayList<>();
        ArrayList<RuleRecord> backupRuleRecordList = new ArrayList<>(viewRules.size());
        File backupDir = createOperationDirectory(
                GodModeApplication.getApplication().getCacheDir(), "backup");
        try {
            prepareFreshDirectory(backupDir);
            Logger.d(TAG, "[Backup] backupRules: temp dir created, package=" + packageName);
                for (RuleRecord viewRule : viewRules) {
                    RuleRecord viewRuleCopy = viewRule.clone();
                    try (ParcelFileDescriptor parcelFileDescriptor = RuleServiceClient.getDefault().openImageFileDescriptor(viewRule.imagePath)) {
                        if (parcelFileDescriptor != null) {
                            try (FileChannel inChannel = new FileInputStream(parcelFileDescriptor.getFileDescriptor()).getChannel()) {
                                File file = new File(backupDir, new File(viewRule.imagePath).getName());
                                try (FileChannel outChannel = new FileOutputStream(file).getChannel()) {
                                    inChannel.transferTo(0, inChannel.size(), outChannel);
                                    viewRuleCopy.imagePath = file.getName();
                                    backupFilePathList.add(file.getPath());
                                }
                            }
                        } else {
                            viewRuleCopy.imagePath = "";
                        }
                    } catch (IOException e) {
                        viewRuleCopy.imagePath = "";
                        Logger.w(TAG, "[Backup] backupRules: skip image for " + viewRule.viewClass + ", failed to copy", e);
                    }
                    if (viewRule.isModifyRule() && !TextUtils.isEmpty(viewRule.modImagePath)
                            && !viewRule.modImagePath.equals(viewRule.imagePath)) {
                        try (ParcelFileDescriptor modPfd = RuleServiceClient.getDefault().openImageFileDescriptor(viewRule.modImagePath)) {
                            if (modPfd != null) {
                                try (FileChannel inChannel = new FileInputStream(modPfd.getFileDescriptor()).getChannel()) {
                                    File file = new File(backupDir, "mod_" + new File(viewRule.modImagePath).getName());
                                    try (FileChannel outChannel = new FileOutputStream(file).getChannel()) {
                                        inChannel.transferTo(0, inChannel.size(), outChannel);
                                        viewRuleCopy.modImagePath = file.getName();
                                        backupFilePathList.add(file.getPath());
                                    }
                                }
                            } else {
                                viewRuleCopy.modImagePath = "";
                            }
                        } catch (IOException e) {
                            viewRuleCopy.modImagePath = "";
                            Logger.w(TAG, "[Backup] backupRules: skip mod image for " + viewRule.viewClass + ", failed to copy", e);
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
                jsonObject.get("version").getAsInt();
                JsonArray jsonArray = jsonObject.getAsJsonArray("rules");
                Logger.d(TAG, "[Backup] restoreRules: manifest parsed, ruleCount=" + jsonArray.size());
                for (int i = 0; i < jsonArray.size(); i++) {
                    String ruleJson = jsonArray.get(i).toString();
                    RuleRecord viewRule = gson.fromJson(ruleJson, RuleRecord.class);

                    // 先保存主图，获取持久化路径
                    Bitmap bitmap = null;
                    if (!TextUtils.isEmpty(viewRule.imagePath)) {
                        String imagePath = new File(restoreDir, viewRule.imagePath).getPath();
                        bitmap = BitmapFactory.decodeFile(imagePath);
                        if (bitmap != null) {
                            String savedPath = RuleServiceClient.getDefault().saveImageFile(viewRule.packageName, bitmap);
                            if (savedPath != null) {
                                viewRule.imagePath = savedPath;
                            }
                        }
                    }

                    // 有修改图时一并保存，组装完整规则
                    if (viewRule.isModifyRule() && !TextUtils.isEmpty(viewRule.modImagePath)) {
                        String modPath = new File(restoreDir, viewRule.modImagePath).getPath();
                        Bitmap modBitmap = BitmapFactory.decodeFile(modPath);
                        if (modBitmap != null) {
                            String savedModPath = RuleServiceClient.getDefault().saveImageFile(viewRule.packageName, modBitmap);
                            if (savedModPath != null) {
                                viewRule.modImagePath = savedModPath;
                            }
                            recycleNullableBitmap(modBitmap);
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
