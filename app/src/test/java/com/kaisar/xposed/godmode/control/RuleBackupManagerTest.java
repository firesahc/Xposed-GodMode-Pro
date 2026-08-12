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

}
