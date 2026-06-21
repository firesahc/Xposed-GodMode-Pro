package com.kaisar.xposed.godmode.service;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;
import static com.kaisar.xposed.godmode.engine.util.GmConstants.DATA_DIR;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Message;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.engine.util.Preconditions;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


/**
 * 规则持久化管理器 — JSON 序列化 + Bitmap 图片 + 工具栏配置持久化。
 * 由 RuleServiceServer 使用。
 */
final class RulePersistManager {

    // /data/misc/godmode
    private static final String BASE_DIR = DATA_DIR;
    // /data/misc/godmode/{package}/package.rule
    static final String RULE_FILE_SUFFIX = ".rule";
    // /data/misc/godmode/{package}/xxxxxxxxx.webp
    static final String IMAGE_FILE_SUFFIX = ".webp";

    static final String TOOLBAR_PREFS_FILE = "toolbar_prefs.json";

    private final Gson mGson;
    private final Logger mLogger;
    private final Handler mHandle;
    private final RuleCacheManager mCacheManager;
    /** 防抖写入延迟(ms) — 多次写入合并为一次持久化操作 */
    private static final long DEBOUNCE_DELAY_MS = 300L;
    /** 防抖队列 — 待写入的包名 */
    private final Map<String, String> mPendingWrites = new HashMap<>();

    RulePersistManager(Gson gson, Logger logger, Handler handle, RuleCacheManager cacheManager) {
        this.mGson = gson;
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCacheManager = cacheManager;
    }

    // ---- 规则加载 ----

    /**
     * 从磁盘加载所有已持久化的规则到内存。
     * 扫描 /data/misc/godmode 目录，将各包的规则反序列化后放入 RuleCacheManager 缓存。
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
                    // compact rule — 清理空规则列表
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

    /** 安全持久化规则：JSON 写入 tmp → rename → chmod */
    void safePersistRules(String packageName, String json) throws IOException {
        synchronized (mPendingWrites) {
            if (mPendingWrites.containsKey(packageName)) {
                // 已有待写入队列，更新 JSON 并调度防抖写入
                mPendingWrites.put(packageName, json);
                scheduleDebouncedWrite(packageName);
                return;
            }
            mPendingWrites.put(packageName, json);
        }
        doPersist(packageName, json);
    }

    private void doPersist(String packageName, String json) throws IOException {
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
        synchronized (mPendingWrites) {
            mPendingWrites.remove(packageName);
        }
        mLogger.d("persisted rules for " + packageName);
    }

    static final int MSG_DEBOUNCE_WRITE = 0x1000;

    private void scheduleDebouncedWrite(String packageName) {
        // 移除旧消息后发送新的防抖消息
        Message msg = mHandle.obtainMessage(MSG_DEBOUNCE_WRITE, packageName);
        mHandle.removeMessages(MSG_DEBOUNCE_WRITE, packageName);
        mHandle.sendMessageDelayed(msg, DEBOUNCE_DELAY_MS);
    }

    /** 由 Handler 调用的防抖写入 */
    void handleDebouncedWrite(String packageName) {
        String json;
        synchronized (mPendingWrites) {
            json = mPendingWrites.get(packageName);
            if (json == null) return;
        }
        try {
            doPersist(packageName, json);
        } catch (IOException e) {
            mLogger.w("debounced persist failed for " + packageName, e);
        }
    }

    /** 保存 Bitmap 为 .webp 文件（HARDWARE → ARGB_8888 转换）*/
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

    /** 清理未被引用的孤儿图片文件 */
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

    // ---- 工具栏配置 ----

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

    // ---- 目录工具方法 ----

    String getBaseDir() throws FileNotFoundException {
        File dir = new File(BASE_DIR);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    /** 检查文件路径是否在 GodMode 数据目录下 */
    boolean isValidImagePath(String filePath) {
        try {
            String base = new File(getBaseDir()).getCanonicalPath();
            String target = new File(filePath).getCanonicalPath();
            // 精确匹配或 base + 分隔符前缀，防止 /data/godmode_evil 此类前缀路径绕过
            return (target.equals(base) || target.startsWith(base + File.separator))
                    && filePath.endsWith(IMAGE_FILE_SUFFIX);
        } catch (IOException e) {
            return false;
        }
    }

    String getAppDataDir(String packageName) throws FileNotFoundException {
        File dir = new File(getBaseDir(), packageName);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        mLogger.e("getAppDataDir: failed to create dir for " + packageName + " at " + dir.getAbsolutePath());
        throw new FileNotFoundException("Cannot create app data dir: " + dir.getAbsolutePath());
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
