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
import java.util.WeakHashMap;

/**
 * 鐟欏嫬鍨幐浣风畽閸栨牜顓搁悶鍡楁珤 閳?JSON 閸樼喎鐡欓崘娆忓弳 + Bitmap 娣囨繂鐡?+ 鐎涖倕鍔归弬鍥︽濞撳懐鎮婇妴?
 * 娴?GodModeManagerService 閹绘劕褰囬惃鍕缁斿浜寸拹锝冣偓?
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
    /** 闂冨弶濮堥崘娆忓弳瀵ゆ儼绻?(ms) 閳?婢舵碍顐艰箛顐︹偓鐔峰綁閺囨潙鎮庨獮鏈佃礋娑撯偓濞嗏€冲晸閸?*/
    private static final long DEBOUNCE_DELAY_MS = 300L;
    /** 闂冨弶濮堥梼鐔峰灙 閳?瀵板懎鍟撻崗銉ф畱閸栧懎鎮?*/
    private final Map<String, String> mPendingWrites = new WeakHashMap<>();

    RulePersistManager(Gson gson, Logger logger, Handler handle, RuleCacheManager cacheManager) {
        this.mGson = gson;
        this.mLogger = logger;
        this.mHandle = handle;
        this.mCacheManager = cacheManager;
    }

    // ---- 鐟欏嫬鍨崝鐘烘祰 ----

    /**
     * 娴犲海顥嗛惄妯哄鏉炶姤澧嶉張澶庮潐閸掓瑦鏆熼幑顔煎煂缂傛挸鐡ㄩ妴?
     * 閸旂姾娴囩紒鎾寸亯闁俺绻冩导鐘烩偓鎺旂舶閺嬪嫰鈧姴鍤遍弫鎵畱 RuleCacheManager 閸愭瑥鍙嗙紓鎾崇摠閵?
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
                    // compact rule 閳?缁夊娅庣粚鐑樻蒋閻?
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

    // ---- 鐟欏嫬鍨幐浣风畽閸?----

    /** 閸樼喎鐡欓崘娆忓弳鐟欏嫬鍨?JSON閿?tmp 閳?rename 閳?chmod */
    void safePersistRules(String packageName, String json) throws IOException {
        synchronized (mPendingWrites) {
            if (mPendingWrites.containsKey(packageName)) {
                // 瀹稿弶婀佸鍛晸閸忋儰鎹㈤崝?閳?閺囧瓨鏌?JSON 楠炶泛娆㈡潻鐔峰晸閸忋儻绱欓梼鍙夊閿?
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
        // 缁夊娅庢稊瀣娑撶儤顒濋崠鍛扮殶鎼达妇娈戦梼鍙夊濞戝牊浼呴敍宀勫櫢缂冾喛顓搁弮璺烘珤
        Message msg = mHandle.obtainMessage(MSG_DEBOUNCE_WRITE, packageName);
        mHandle.removeMessages(MSG_DEBOUNCE_WRITE, packageName);
        mHandle.sendMessageDelayed(msg, DEBOUNCE_DELAY_MS);
    }

    /** 閻?Handler 鐠嬪啰鏁ら惃鍕Щ閹舵牕鍟撻崗?*/
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

    /** 娣囨繂鐡?Bitmap 娑?.webp 閺傚洣娆㈤敍灞筋槱閻?HARDWARE 閳?ARGB_8888 鏉烆剚宕?*/
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

    /** 濞撳懐鎮婇張顏囶潶娴犺缍嶇憴鍕灟瀵洜鏁ら惃鍕劃缁斿娴橀悧鍥ㄦ瀮娴?*/
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

    // ---- 瀹搞儱鍙块弽蹇撲焊婵?----

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

    // ---- 鐠侯垰绶炲銉ュ徔 ----

    String getBaseDir() throws FileNotFoundException {
        File dir = new File(BASE_DIR);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException();
    }

    /** 閺嶏繝鐛欓弬鍥︽鐠侯垰绶為弰顖氭儊娑撳搫鎮庡▔鏇犳畱 GodMode 閸ュ墽澧栭弬鍥︽鐠侯垰绶?*/
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
