package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kaisar.xposed.godmode.engine.rule.RuleEffect;
import com.kaisar.xposed.godmode.engine.util.FileUtils;
import com.kaisar.xposed.godmode.engine.util.ZipUtils;
import com.kaisar.xposed.godmode.rule.RuleRecord;

/**
 * ZIP V1 备份布局 golden 合同 — 纯 JVM 锁定 {@link RuleBackupManager#writeArchive}
 * 的归档组装行为（docs 合同要求锁定 ZIP V1 布局），经 ZipUtils 往返验证：
 * 条目集合恒为平铺 {manifest.json} ∪ 主图名 ∪ mod_ 前缀名。
 */
public final class BackupLayoutGoldenTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final Map<String, byte[]> localImages = new LinkedHashMap<>();

    @Test
    public void roundTripProducesFlatLayoutWithSubstitutedEntryPaths() throws Exception {
        File workDir = temporaryFolder.newFolder("work");
        byte[] mainImage = bytes("MAIN-IMAGE-BYTES");
        byte[] modImage = bytes("MODIFIED-IMAGE-BYTES");
        localImages.put("/data/images/photo.webp", mainImage);
        localImages.put("/data/images/tint.webp", modImage);
        RuleRecord rule = modifyRule(baseRule("/data/images/photo.webp"),
                "/data/images/tint.webp");

        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        List<String> written = RuleBackupManager.writeArchive(archiveOut, workDir,
                "com.example", Collections.singletonList(rule), localOpener());
        assertEquals(Arrays.asList(
                new File(workDir, "photo.webp").getPath(),
                new File(workDir, "mod_tint.webp").getPath(),
                new File(workDir, "manifest.json").getPath()),
                written);

        File extracted = temporaryFolder.newFolder("extracted");
        ZipUtils.uncompress(new ByteArrayInputStream(archiveOut.toByteArray()),
                extracted.getPath());
        assertEquals(new HashSet<>(Arrays.asList(
                "manifest.json", "photo.webp", "mod_tint.webp")),
                extractedFileNames(extracted));

        JsonObject manifest = readManifest(extracted);
        assertEquals(1, manifest.get("version").getAsInt());
        assertEquals("com.example", manifest.get("packageName").getAsString());
        JsonArray rules = manifest.getAsJsonArray("rules");
        assertEquals(1, rules.size());
        JsonObject record = rules.get(0).getAsJsonObject();
        // imagePath/modImagePath 已替换为归档内条目名
        assertEquals("photo.webp", record.get("img_path").getAsString());
        assertEquals("mod_tint.webp", record.get("mod_img_path").getAsString());

        assertArrayEquals(mainImage, readFileBytes(new File(extracted, "photo.webp")));
        assertArrayEquals(modImage, readFileBytes(new File(extracted, "mod_tint.webp")));
    }

    @Test
    public void sameSourceImagesShareSingleArchiveEntry() throws Exception {
        File workDir = temporaryFolder.newFolder("work");
        localImages.put("/pool/shared.webp", bytes("SHARED"));
        localImages.put("/pool/same.webp", bytes("SAME"));
        RuleRecord first = baseRule("/pool/shared.webp");
        RuleRecord second = baseRule("/pool/shared.webp");
        // mod 图与主图同源：不产生独立条目，mod 路径复用主图条目名。
        RuleRecord third = modifyRule(baseRule("/pool/same.webp"), "/pool/same.webp");

        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        List<String> written = RuleBackupManager.writeArchive(archiveOut, workDir,
                "com.example", Arrays.asList(first, second, third), localOpener());
        assertEquals(3, written.size());

        File extracted = temporaryFolder.newFolder("extracted-shared");
        ZipUtils.uncompress(new ByteArrayInputStream(archiveOut.toByteArray()),
                extracted.getPath());
        assertEquals(new HashSet<>(Arrays.asList(
                "manifest.json", "shared.webp", "same.webp")),
                extractedFileNames(extracted));

        JsonObject manifest = readManifest(extracted);
        JsonArray rules = manifest.getAsJsonArray("rules");
        assertEquals(3, rules.size());
        assertEquals("shared.webp",
                rules.get(0).getAsJsonObject().get("img_path").getAsString());
        assertEquals("shared.webp",
                rules.get(1).getAsJsonObject().get("img_path").getAsString());
        JsonObject thirdRecord = rules.get(2).getAsJsonObject();
        assertEquals("same.webp", thirdRecord.get("img_path").getAsString());
        assertEquals("same.webp", thirdRecord.get("mod_img_path").getAsString());
    }

    @Test
    public void conflictingSourceNamesGetNumericSuffixes() throws Exception {
        File workDir = temporaryFolder.newFolder("work");
        byte[] alpha = bytes("ALPHA");
        byte[] beta = bytes("BETA");
        byte[] gamma = bytes("GAMMA");
        localImages.put("/a/preview.webp", alpha);
        localImages.put("/b/preview.webp", beta);
        localImages.put("/c/preview.webp", gamma);
        RuleRecord first = baseRule("/a/preview.webp");
        RuleRecord second = baseRule("/b/preview.webp");
        RuleRecord third = modifyRule(baseRule(null), "/c/preview.webp");

        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        List<String> written = RuleBackupManager.writeArchive(archiveOut, workDir,
                "com.example", Arrays.asList(first, second, third), localOpener());
        assertEquals(4, written.size());

        File extracted = temporaryFolder.newFolder("extracted-conflict");
        ZipUtils.uncompress(new ByteArrayInputStream(archiveOut.toByteArray()),
                extracted.getPath());
        assertEquals(new HashSet<>(Arrays.asList(
                "manifest.json", "preview.webp", "preview_1.webp", "mod_preview.webp")),
                extractedFileNames(extracted));

        JsonObject manifest = readManifest(extracted);
        JsonArray rules = manifest.getAsJsonArray("rules");
        assertEquals("preview.webp",
                rules.get(0).getAsJsonObject().get("img_path").getAsString());
        assertEquals("preview_1.webp",
                rules.get(1).getAsJsonObject().get("img_path").getAsString());
        assertEquals("mod_preview.webp",
                rules.get(2).getAsJsonObject().get("mod_img_path").getAsString());
        assertArrayEquals(alpha, readFileBytes(new File(extracted, "preview.webp")));
        assertArrayEquals(beta, readFileBytes(new File(extracted, "preview_1.webp")));
        assertArrayEquals(gamma, readFileBytes(new File(extracted, "mod_preview.webp")));
    }

    @Test
    public void emptySourceNameFallsBackToImageWebp() throws Exception {
        File workDir = temporaryFolder.newFolder("work");
        localImages.put("/", bytes("ROOT"));
        RuleRecord rule = baseRule("/");

        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        List<String> written = RuleBackupManager.writeArchive(archiveOut, workDir,
                "com.example", Collections.singletonList(rule), localOpener());
        assertEquals(2, written.size());

        File extracted = temporaryFolder.newFolder("extracted-fallback");
        ZipUtils.uncompress(new ByteArrayInputStream(archiveOut.toByteArray()),
                extracted.getPath());
        assertEquals(new HashSet<>(Arrays.asList("manifest.json", "image.webp")),
                extractedFileNames(extracted));
        JsonObject manifest = readManifest(extracted);
        assertEquals("image.webp", manifest.getAsJsonArray("rules").get(0)
                .getAsJsonObject().get("img_path").getAsString());
    }

    @Test
    public void foreignPackageRuleAbortsArchive() throws Exception {
        File workDir = temporaryFolder.newFolder("work");
        localImages.put("/x/photo.webp", bytes("FOREIGN"));
        RuleRecord foreign = new RuleRecord("label", "com.other", "1.0", 1, 68,
                "/x/photo.webp", "alias", 1, 2, 3, 4, new int[]{1},
                "Activity", "TextView", "id/title", "", "", 0, 1L);

        try {
            RuleBackupManager.writeArchive(new ByteArrayOutputStream(), workDir,
                    "com.example", Collections.singletonList(foreign), localOpener());
            fail("Expected IOException for mismatched package");
        } catch (IOException expected) {
            assertTrue(expected.getMessage()
                    .contains("Rule package does not match backup package"));
        }
    }

    @Test
    public void unavailableMainImageAbortsArchive() throws Exception {
        File workDir = temporaryFolder.newFolder("work");
        RuleRecord rule = baseRule("/missing/photo.webp");

        try {
            RuleBackupManager.writeArchive(new ByteArrayOutputStream(), workDir,
                    "com.example", Collections.singletonList(rule), localOpener());
            fail("Expected IOException for unavailable image source");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("required main image is unavailable"));
        }
    }

    /** 本地图片来源 — 将规则路径映射到内存字节，替代生产的服务端描述符实现。 */
    private RuleBackupManager.BackupImageOpener localOpener() {
        return imagePath -> {
            byte[] content = localImages.get(imagePath);
            return content == null ? null : new ByteArrayInputStream(content);
        };
    }

    private static JsonObject readManifest(File extractedDir) throws IOException {
        String json = FileUtils.readTextFile(new File(extractedDir, "manifest.json"), 0, null);
        return new GsonBuilder().create().fromJson(json, JsonObject.class);
    }

    private static Set<String> extractedFileNames(File dir) {
        Set<String> names = new HashSet<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) names.add(file.getName());
            }
        }
        return names;
    }

    private static byte[] readFileBytes(File file) throws IOException {
        return Files.readAllBytes(file.toPath());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static RuleRecord baseRule(String imagePath) {
        return new RuleRecord("label", "com.example", "1.0", 1, 68,
                imagePath, "alias", 1, 2, 3, 4, new int[]{1},
                "Activity", "TextView", "id/title", "", "", 0, 1L);
    }

    private static RuleRecord modifyRule(RuleRecord base, String modImagePath) {
        return base.withEffect(RuleEffect.fromWireValues(new RuleEffect.WireValues.Builder()
                .ruleTag("modify")
                .modImagePath(modImagePath)
                .build()));
    }
}
