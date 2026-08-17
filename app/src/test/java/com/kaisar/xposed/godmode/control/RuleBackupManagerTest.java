package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import com.kaisar.xposed.godmode.rule.RuleRecord;
import com.kaisar.xposed.godmode.engine.rule.RuleEffect;

public final class RuleBackupManagerTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void concurrentOperationsReceiveIndependentDirectories()
            throws Exception {
        File cache = temporaryFolder.newFolder("cache");

        File first = RuleBackupManager.createOperationDirectory(cache, "restore");
        File second = RuleBackupManager.createOperationDirectory(cache, "restore");
        RuleBackupManager.prepareFreshDirectory(first);
        RuleBackupManager.prepareFreshDirectory(second);
        File firstSentinel = new File(first, "first.tmp");
        File secondSentinel = new File(second, "second.tmp");
        assertTrue(firstSentinel.createNewFile());
        assertTrue(secondSentinel.createNewFile());

        assertNotEquals(first, second);
        assertEquals(new File(cache, "restore"), first.getParentFile());
        assertEquals(new File(cache, "restore"), second.getParentFile());
        assertTrue(first.getName().length() > 10);
        assertTrue(second.getName().length() > 10);
        assertTrue(firstSentinel.isFile());
        assertTrue(secondSentinel.isFile());
    }

    @Test
    public void prepareFreshDirectoryClearsExistingContents() throws Exception {
        File directory = temporaryFolder.newFolder("operation");
        assertTrue(new File(directory, "stale.tmp").createNewFile());

        RuleBackupManager.prepareFreshDirectory(directory);

        assertTrue(directory.isDirectory());
        assertEquals(0, directory.list().length);
    }

    @Test
    public void prepareFreshDirectoryRejectsFileParent() throws Exception {
        File parent = temporaryFolder.newFile("not-a-directory");
        File directory = new File(parent, "operation");

        try {
            RuleBackupManager.prepareFreshDirectory(directory);
            fail("Expected IOException");
        } catch (IOException expected) {
            assertFalse(directory.exists());
        }
    }

    @Test
    public void imageEntryRegistryDeduplicatesSourcesAndPreservesExtensions() {
        RuleBackupManager.ImageEntryRegistry entries =
                new RuleBackupManager.ImageEntryRegistry();

        String first = entries.reserve("preview.webp");
        entries.record("/first/preview.webp", first);
        String second = entries.reserve("preview.webp");
        entries.record("/second/preview.webp", second);
        String third = entries.reserve("preview.webp");

        assertEquals("preview.webp", first);
        assertEquals("preview_1.webp", second);
        assertEquals("preview_2.webp", third);
        assertEquals(first, entries.find("/first/preview.webp"));
        assertEquals(second, entries.find("/second/preview.webp"));
    }

    @Test
    public void restoredImageMustRemainInsideOperationDirectory() throws Exception {
        File restoreDir = temporaryFolder.newFolder("restore");
        File image = new File(restoreDir, "preview.webp");
        assertTrue(image.createNewFile());
        File outside = temporaryFolder.newFile("outside.webp");

        assertEquals(image.getCanonicalFile(),
                RuleBackupManager.resolveRestoredFile(restoreDir, "preview.webp"));
        assertEquals(null,
                RuleBackupManager.resolveRestoredFile(restoreDir, "../outside.webp"));
        assertEquals(null,
                RuleBackupManager.resolveRestoredFile(restoreDir,
                        outside.getAbsolutePath()));
        assertEquals(null,
                RuleBackupManager.resolveRestoredFile(restoreDir, "missing.webp"));
    }

    @Test
    public void prepareBackupRecordDoesNotMutateNonModifyInput() {
        RuleRecord input = new RuleRecord("label", "com.example", "1", 1, 68,
                "preview.webp", "alias", 1, 2, 3, 4, new int[]{1},
                "Activity", "TextView", "id/title", "", "", 0, 1L);
        input = input.withEffect(RuleEffect.fromWireValues(new RuleEffect.WireValues.Builder()
                .modImagePath("/data/original-mod.webp").build()));

        RuleRecord copy = RuleBackupManager.prepareBackupRecord(input);

        assertEquals("/data/original-mod.webp", input.getModImagePath());
        assertEquals("", copy.getModImagePath());
        assertFalse(input == copy);
    }

    @Test
    public void selectedBackupUsesLastAuthoritativeDuplicateSlot() {
        RuleRecord first = ruleWithAlias("first");
        RuleRecord last = ruleWithAlias("last");

        List<RuleRecord> selected = RuleBackupManager.selectCurrentRules(
                Arrays.asList(first, last), Arrays.asList(first, last));

        assertEquals(2, selected.size());
        assertEquals("last", selected.get(0).alias);
        assertEquals("last", selected.get(1).alias);
    }

    private static RuleRecord ruleWithAlias(String alias) {
        return new RuleRecord("label", "com.example", "1", 1, 68,
                "preview.webp", alias, 1, 2, 3, 4, new int[]{1},
                "Activity", "TextView", "id/title", "", "", 0, 1L);
    }

}
