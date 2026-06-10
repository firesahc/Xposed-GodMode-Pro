
package com.kaisar.xposed.godmode.util;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
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
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
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

/**
 * Rule backup util
 */
public final class BackupUtils {

    private static final int VERSION = 1;
    private static final String MANIFEST_FILE = "manifest.json";

    private BackupUtils() {
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
        File backupDir = new File(GodModeApplication.getApplication().getCacheDir(), "backup");
        if (!backupDir.exists() || FileUtils.delete(backupDir.getPath())) {
            boolean ok = backupDir.mkdirs();
            if (!ok) throw new BackupException("Create backup directory failed.");
            Logger.d(TAG, "[Backup] backupRules: temp dir created, package=" + packageName);
            try {
                for (RuleRecord viewRule : viewRules) {
                    RuleRecord viewRuleCopy = viewRule.clone();
                    try {
                        ParcelFileDescriptor parcelFileDescriptor = RuleServiceClient.getDefault().openImageFileDescriptor(viewRule.imagePath);
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
                        try {
                            ParcelFileDescriptor modPfd = RuleServiceClient.getDefault().openImageFileDescriptor(viewRule.modImagePath);
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
                FileUtils.delete(backupDir.getPath());
            }
        }
    }

    public static int restoreRules(Uri fromUri) throws RestoreException {
        Logger.i(TAG, "[Backup] restoreRules: start, uri=" + fromUri);
        File restoreDir = new File(GodModeApplication.getApplication().getCacheDir(), "restore");
        if (!restoreDir.exists() || FileUtils.delete(restoreDir.getPath())) {
            boolean ok = restoreDir.mkdirs();
            if (!ok) throw new RestoreException("Create restore directory failed.");
            try {
                try (InputStream in = GodModeApplication.getApplication().getContentResolver().openInputStream(fromUri)) {
                    ZipUtils.uncompress(in, restoreDir.getPath());
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
                    if (!TextUtils.isEmpty(viewRule.imagePath)) {
                        String imagePath = new File(restoreDir, viewRule.imagePath).getPath();
                        Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
                        RuleServiceClient.getDefault().writeRule(viewRule.packageName, viewRule, bitmap);
                        recycleNullableBitmap(bitmap);
                    } else {
                        RuleServiceClient.getDefault().writeRule(viewRule.packageName, viewRule, null);
                    }
                    if (viewRule.isModifyRule() && !TextUtils.isEmpty(viewRule.modImagePath)) {
                        String modPath = new File(restoreDir, viewRule.modImagePath).getPath();
                        Bitmap modBitmap = BitmapFactory.decodeFile(modPath);
                        if (modBitmap != null) {
                            String savedPath = RuleServiceClient.getDefault().saveImageFile(viewRule.packageName, modBitmap);
                            if (savedPath != null) {
                                viewRule.modImagePath = savedPath;
                                RuleServiceClient.getDefault().updateRule(viewRule.packageName, viewRule);
                            }
                            recycleNullableBitmap(modBitmap);
                        }
                    }
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
                FileUtils.delete(restoreDir.getPath());
            }
        }
        return 0;
    }
}
