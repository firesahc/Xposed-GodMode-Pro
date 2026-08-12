package com.kaisar.xposed.godmode.engine.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipUtilsTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void extractsContentAtExactLimits() throws Exception {
        byte[] content = new byte[]{1, 2, 3, 4};
        byte[] archive = archive(entry("data.bin", content));
        File destination = temporaryFolder.newFolder("exact");

        ZipUtils.uncompress(new ByteArrayInputStream(archive),
                destination.getPath(), content.length, 1);

        assertArrayEquals(content,
                Files.readAllBytes(new File(destination, "data.bin").toPath()));
    }

    @Test
    public void rejectsContentOneByteOverLimit() throws Exception {
        byte[] archive = archive(entry("data.bin", new byte[]{1, 2, 3, 4}));
        File destination = temporaryFolder.newFolder("too-large");

        expectIOException(() -> ZipUtils.uncompress(
                new ByteArrayInputStream(archive), destination.getPath(), 3, 1));
    }

    @Test
    public void rejectsEntryCountOverLimit() throws Exception {
        byte[] archive = archive(
                entry("first", new byte[0]),
                entry("second", new byte[0]));
        File destination = temporaryFolder.newFolder("too-many");

        expectIOException(() -> ZipUtils.uncompress(
                new ByteArrayInputStream(archive), destination.getPath(), 0, 1));
    }

    @Test
    public void rejectsCanonicalDuplicateAliases() throws Exception {
        byte[] archive = archive(
                entry("same.txt", new byte[]{1}),
                entry("./same.txt", new byte[]{2}));
        File destination = temporaryFolder.newFolder("duplicate");

        expectIOException(() -> ZipUtils.uncompress(
                new ByteArrayInputStream(archive), destination.getPath(), 2, 2));
    }

    @Test
    public void rejectsPathTraversal() throws Exception {
        byte[] archive = archive(entry("../escape.txt", new byte[]{1}));
        File destination = temporaryFolder.newFolder("traversal");

        expectIOException(() -> ZipUtils.uncompress(
                new ByteArrayInputStream(archive), destination.getPath(), 1, 1));
    }

    @Test
    public void extractsNestedDirectoryEntry() throws Exception {
        byte[] archive = archive(
                directory("nested/"),
                entry("nested/data.bin", new byte[]{7}));
        File destination = temporaryFolder.newFolder("nested");

        ZipUtils.uncompress(new ByteArrayInputStream(archive),
                destination.getPath(), 1, 2);

        assertTrue(new File(destination, "nested/data.bin").isFile());
    }

    private static ArchiveEntry entry(String name, byte[] content) {
        return new ArchiveEntry(name, content, false);
    }

    private static ArchiveEntry directory(String name) {
        return new ArchiveEntry(name, new byte[0], true);
    }

    private static byte[] archive(ArchiveEntry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (ArchiveEntry entry : entries) {
                ZipEntry zipEntry = new ZipEntry(entry.name);
                zip.putNextEntry(zipEntry);
                if (!entry.directory) {
                    zip.write(entry.content);
                }
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void expectIOException(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class ArchiveEntry {
        final String name;
        final byte[] content;
        final boolean directory;

        ArchiveEntry(String name, byte[] content, boolean directory) {
            this.name = name;
            this.content = content;
            this.directory = directory;
        }
    }
}
