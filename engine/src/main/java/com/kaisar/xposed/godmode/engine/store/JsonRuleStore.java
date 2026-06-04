package com.kaisar.xposed.godmode.engine.store;

import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXG;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXO;
import static com.kaisar.xposed.godmode.engine.util.FileUtils.S_IRWXU;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Environment;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.kaisar.xposed.godmode.engine.rule.ViewRule;
import com.kaisar.xposed.godmode.engine.util.FileUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * IRuleStore 的 JSON 文件实现。
 * 使用 .tmp → rename 原子写入策略，Gson 序列化，Webp 图片存储。
 * 存储路径：/data/system/godmode/{packageName}/{packageName}.rule
 */
public final class JsonRuleStore implements IRuleStore {

    private static final String BASE_DIR = String.format("%s/misc/%s",
            Environment.getDataDirectory().getAbsolutePath(), "godmode");
    private static final String RULE_SUFFIX = ".rule";
    private static final String IMAGE_SUFFIX = ".webp";

    private final Gson mGson;

    public JsonRuleStore() {
        this(new Gson());
    }

    public JsonRuleStore(Gson gson) {
        this.mGson = gson;
    }

    @Override
    public Map<String, Map<String, List<ViewRule>>> loadAll() throws IOException {
        File dataDir = new File(getBaseDir());
        Map<String, Map<String, List<ViewRule>>> allRules = new HashMap<>();
        File[] packageDirs = dataDir.listFiles(File::isDirectory);
        if (packageDirs != null) {
            for (File packageDir : packageDirs) {
                try {
                    String pkg = packageDir.getName();
                    Map<String, List<ViewRule>> rules = load(pkg);
                    if (rules != null && !rules.isEmpty()) {
                        allRules.put(pkg, rules);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return allRules;
    }

    @Override
    public Map<String, List<ViewRule>> load(String packageName) throws IOException {
        String json = FileUtils.readTextFile(
                getRuleFilePath(packageName), 0, null);
        @SuppressWarnings("unchecked")
        Map<String, List<ViewRule>> rules =
                mGson.fromJson(json, Map.class);
        if (rules == null) return new HashMap<>();
        // 清理空条目
        Iterator<Map.Entry<String, List<ViewRule>>> iter = rules.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, List<ViewRule>> entry = iter.next();
            List<ViewRule> value = entry.getValue();
            if (value == null || value.isEmpty()) {
                iter.remove();
            }
        }
        return rules;
    }

    @Override
    public void save(String packageName, Map<String, List<ViewRule>> rules)
            throws IOException {
        String json = mGson.toJson(rules);
        File appDir = new File(getBaseDir(), packageName);
        if (!appDir.exists() && !appDir.mkdirs()) {
            throw new IOException("Failed to create dir: " + appDir);
        }
        File ruleFile = new File(appDir, packageName + RULE_SUFFIX);
        File tmpFile = new File(appDir, packageName + RULE_SUFFIX + ".tmp");
        FileUtils.stringToFile(tmpFile, json);
        if (!tmpFile.renameTo(ruleFile)) {
            if (tmpFile.exists()) tmpFile.delete();
            throw new IOException("Failed to atomically rename: " + ruleFile);
        }
        FileUtils.setPermissions(ruleFile, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
    }

    @Override
    public String saveBitmap(String packageName, Bitmap bitmap) throws IOException {
        Bitmap toSave = bitmap;
        if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            toSave = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(),
                    Bitmap.Config.ARGB_8888);
            new Canvas(toSave).drawBitmap(bitmap, 0, 0, null);
        }
        File dir = new File(getBaseDir(), packageName);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create dir: " + dir);
        }
        File file = new File(dir, System.currentTimeMillis() + IMAGE_SUFFIX);
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (toSave.compress(Bitmap.CompressFormat.WEBP, 80, out)) {
                FileUtils.setPermissions(file, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
                if (toSave != bitmap && !toSave.isRecycled()) toSave.recycle();
                return file.getAbsolutePath();
            }
            throw new IOException("Failed to compress bitmap");
        }
    }

    @Override
    public void delete(String packageName) throws IOException {
        File dir = new File(getBaseDir(), packageName);
        if (dir.exists()) FileUtils.delete(dir);
    }

    @Override
    public void delete(String packageName, ViewRule rule) throws IOException {
        // 删除操作在内存层面由 RuleCacheManager 完成，
        // 此方法仅负责重新持久化更新后的规则。
        Map<String, List<ViewRule>> rules = load(packageName);
        if (rules == null) return;
        List<ViewRule> list = rules.get(rule.activityClass);
        if (list != null) {
            list.remove(rule);
            if (list.isEmpty()) rules.remove(rule.activityClass);
        }
        if (rules.isEmpty()) {
            delete(packageName);
        } else {
            save(packageName, rules);
        }
    }

    @Override
    public void cleanOrphanImages() throws IOException {
        File dataDir = new File(getBaseDir());
        File[] packageDirs = dataDir.listFiles(File::isDirectory);
        if (packageDirs == null) return;
        for (File pkgDir : packageDirs) {
            File[] images = pkgDir.listFiles(
                    (dir, name) -> name.endsWith(IMAGE_SUFFIX));
            if (images == null || images.length == 0) continue;
            Map<String, List<ViewRule>> rules;
            try {
                rules = load(pkgDir.getName());
            } catch (Exception e) {
                continue;
            }
            java.util.Set<String> referenced = new java.util.HashSet<>();
            if (rules != null) {
                for (List<ViewRule> vrs : rules.values()) {
                    for (ViewRule vr : vrs) {
                        if (vr.imagePath != null) referenced.add(vr.imagePath);
                        if (vr.modImagePath != null) referenced.add(vr.modImagePath);
                    }
                }
            }
            for (File img : images) {
                if (!referenced.contains(img.getAbsolutePath())) {
                    FileUtils.delete(img);
                }
            }
        }
    }

    private String getBaseDir() throws FileNotFoundException {
        File dir = new File(BASE_DIR);
        if (dir.exists() || dir.mkdirs()) {
            FileUtils.setPermissions(dir, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return dir.getAbsolutePath();
        }
        throw new FileNotFoundException(BASE_DIR);
    }

    private String getRuleFilePath(String packageName) throws IOException {
        File dir = new File(getBaseDir(), packageName);
        File file = new File(dir, packageName + RULE_SUFFIX);
        if (file.exists() || file.createNewFile()) {
            FileUtils.setPermissions(file, S_IRWXU | S_IRWXG | S_IRWXO, -1, -1);
            return file.getAbsolutePath();
        }
        throw new FileNotFoundException(file.getAbsolutePath());
    }
}
