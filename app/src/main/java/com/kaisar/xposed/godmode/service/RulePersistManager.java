package com.kaisar.xposed.godmode.service;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.kaisar.xposed.godmode.engine.util.CommonUtils;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.util.Logger;
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
import java.util.WeakHashMap;

/**
 * 瑙勫垯鎸佷箙鍖栫鐞嗗櫒 鈥?JSON 鍘熷瓙鍐欏叆 + Bitmap 淇濆瓨 + 瀛ゅ効鏂囦欢娓呯悊銆?
 * 浠?GodModeManagerService 鎻愬彇鐨勭嫭绔嬭亴璐ｃ€?
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
    /** 闃叉姈鍐欏叆寤惰繜 (ms) 鈥?澶氭蹇€熷彉鏇村悎骞朵负涓€娆″啓鍏?*/
    private static final long DEBOUNCE_DELAY_MS = 300L;
    /** 闃叉姈闃熷垪 鈥?寰呭啓鍏ョ殑鍖呭悕 */
    private final Map<String, String> mPendingWrites = new WeakHashMap<>();

    RulePersistManager(Gson gson, Logger logger, Handler handle, RuleCacheManager cacheManager) {
        this.mGson = gson;
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCacheManager = cacheManager;
    }

    // ---- 瑙勫垯鍔犺浇 ----

    /**
     * 浠庣鐩樺姞杞芥墍鏈夎鍒欐暟鎹埌缂撳瓨銆?
     * 鍔犺浇缁撴灉閫氳繃浼犻€掔粰鏋勯€犲嚱鏁扮殑 RuleCacheManager 鍐欏叆缂撳瓨銆?
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
                    // compact rule 鈥?绉婚櫎绌烘潯鐩?
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

    // ---- 瑙勫垯鎸佷箙鍖?----

    /** 鍘熷瓙鍐欏叆瑙勫垯 JSON锛?tmp 鈫?rename 鈫?chmod */
    void safePersistRules(String packageName, String json) throws IOException {
        synchronized (mPendingWrites) {
            if (mPendingWrites.containsKey(packageName)) {
                // 宸叉湁寰呭啓鍏ヤ换鍔?鈥?鏇存柊 JSON 骞跺欢杩熷啓鍏ワ紙闃叉姈锛?
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
    }

    static final int MSG_DEBOUNCE_WRITE = 0x1000;

    private void scheduleDebouncedWrite(String packageName) {
        // 绉婚櫎涔嬪墠涓烘鍖呰皟搴︾殑闃叉姈娑堟伅锛岄噸缃鏃跺櫒
        Message msg = mHandle.obtainMessage(MSG_DEBOUNCE_WRITE, packageName);
        mHandle.removeMessages(MSG_DEBOUNCE_WRITE, packageName);
        mHandle.sendMessageDelayed(msg, DEBOUNCE_DELAY_MS);
    }

    /** 鐢?Handler 璋冪敤鐨勯槻鎶栧啓鍏?*/
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

    /** 淇濆瓨 Bitmap 涓?.webp 鏂囦欢锛屽鐞?HARDWARE 鈫?ARGB_8888 杞崲 */
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

    /** 娓呯悊鏈浠讳綍瑙勫垯寮曠敤鐨勫绔嬪浘鐗囨枃浠?*/
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

    // ---- 宸ュ叿鏍忓亸濂?----

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

    // ---- 璺緞宸ュ叿 ----

    String getBaseDir() throws FileNotFoundException {
        File dir = new File(BASE_DIR);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    /** 鏍￠獙鏂囦欢璺緞鏄惁涓哄悎娉曠殑 GodMode 鍥剧墖鏂囦欢璺緞 */
    boolean isValidImagePath(String filePath) {
        try {
            return new File(filePath).getCanonicalPath()
                    .startsWith(new File(getBaseDir()).getCanonicalPath())
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
