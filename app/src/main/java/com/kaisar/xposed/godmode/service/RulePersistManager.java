package com.kaisar.xposed.godmode.service;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Environment;
import android.os.Handler;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.util.Preconditions;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 规则持久化管理器 — JSON 原子写入 + Bitmap 保存 + 孤儿文件清理。
 * 从 GodModeManagerService 提取的独立职责。
 */
final class RulePersistManager {

    // /data/system/godmode
    private static final String BASE_DIR = String.format("%s/misc/%s",
            Environment.getDataDirectory().getAbsolutePath(), "godmode");
    // /data/system/godmode/{package}/package.rule
    static final String RULE_FILE_SUFFIX = ".rule";
    // /data/system/godmode/{package}/xxxxxxxxx.webp
    static final String IMAGE_FILE_SUFFIX = ".webp";

    static final String TOOLBAR_PREFS_FILE = "toolbar_prefs.json";

    private final Gson mGson;
    private final Logger mLogger;
    private final Handler mHandle;
    private final RuleCacheManager mCacheManager;

    RulePersistManager(Gson gson, Logger logger, Handler handle, RuleCacheManager cacheManager) {
        this.mGson = gson;
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCacheManager = cacheManager;
    }

    // ---- 规则加载 ----

    /**
     * 从磁盘加载所有规则数据到缓存。
     * 加载结果通过传递给构造函数的 RuleCacheManager 写入缓存。
     */
    void loadRuleData() throws IOException {
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
                    // compact rule — 移除空条目
                    Iterator<Map.Entry<String, List<RuleRecord>>> iterator = rules.entrySet().iterator();
                    while (iterator.hasNext()) {
                        Map.Entry<String, List<RuleRecord>> listEntry = iterator.next();
                        List<RuleRecord> value = listEntry.getValue();
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
            mCacheManager.putAll(appRules);
        }
    }

    // ---- 规则持久化 ----

    /** 原子写入规则 JSON：.tmp → rename → chmod */
    void safePersistRules(String packageName, String json) throws IOException {
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

    /** 保存 Bitmap 为 .webp 文件，处理 HARDWARE → ARGB_8888 转换 */
    String saveBitmap(Bitmap bitmap, String dir) {
        try {
            Bitmap bitmapToSave = bitmap;
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                bitmapToSave = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                new Canvas(bitmapToSave).drawBitmap(bitmap, 0, 0, null);
            }
            File file = new File(dir,
                    System.currentTimeMillis() + IMAGE_FILE_SUFFIX);
            try (FileOutputStream out = new FileOutputStream(file)) {
                if (bitmapToSave.compress(Bitmap.CompressFormat.WEBP, 80, out)) {
                    FileUtils.setPermissions(file, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
                    return file.getAbsolutePath();
                }
                throw new FileNotFoundException("bitmap can't compress to " + file.getAbsolutePath());
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

    /** 清理未被任何规则引用的孤立图片文件 */
    void cleanAllOrphanImages() {
        try {
            File dataDir = new File(getBaseDir());
            File[] packageDirs = dataDir.listFiles(File::isDirectory);
            if (packageDirs == null) return;
            for (File packageDir : packageDirs) {
                File[] imageFiles = packageDir.listFiles(
                        (dir, name) -> name.endsWith(IMAGE_FILE_SUFFIX));
                if (imageFiles == null || imageFiles.length == 0) continue;
                mCacheManager.collectReferencedImages(packageDir, packageDir.getName(),
                        (dir, referenced) -> {
                            for (File f : imageFiles) {
                                if (!referenced.contains(f.getAbsolutePath())) {
                                    FileUtils.delete(f);
                                }
                            }
                        });
            }
        } catch (FileNotFoundException e) {
            mLogger.w("orphan cleanup: base dir not found", e);
        }
    }

    // ---- 工具栏偏好 ----

    String loadToolbarHiddenItems() {
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

    void persistToolbarHiddenItems(String items) {
        try {
            File prefsFile = new File(getBaseDir(), TOOLBAR_PREFS_FILE);
            FileUtils.stringToFile(prefsFile, items);
            FileUtils.setPermissions(prefsFile, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
        } catch (Exception e) {
            mLogger.w("persist toolbar prefs failed", e);
        }
    }

    // ---- 路径工具 ----

    String getBaseDir() throws FileNotFoundException {
        File dir = new File(BASE_DIR);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    /** 校验文件路径是否为合法的 GodMode 图片文件路径 */
    boolean isValidImagePath(String filePath) {
        try {
            return filePath.startsWith(getBaseDir())
                    && filePath.endsWith(IMAGE_FILE_SUFFIX);
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    String getAppDataDir(String packageName) throws FileNotFoundException {
        File dir = new File(getBaseDir(), packageName);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    String getAppRuleFilePath(String packageName) throws IOException {
        File file = new File(getAppDataDir(packageName), packageName + RULE_FILE_SUFFIX);
        if (file.exists() || file.createNewFile()) {
            FileUtils.setPermissions(file, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return file.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }
}
