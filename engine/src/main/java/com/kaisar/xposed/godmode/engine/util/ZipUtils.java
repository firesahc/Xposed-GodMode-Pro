package com.kaisar.xposed.godmode.engine.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Created by jrsen on 17-12-13.
 */

public final class ZipUtils {

    public static void compress(OutputStream out, String... filePaths) throws IOException {
        try (ZipOutputStream zipOut = new ZipOutputStream(out)) {
            for (String filePath : filePaths) {
                File file = new File(filePath);
                ZipEntry e = new ZipEntry(file.getName());
                zipOut.putNextEntry(e);
                try (FileInputStream in = new FileInputStream(file)) {
                    if (!FileUtils.copy(in, zipOut)) {
                        throw new IOException("Failed to copy file: " + filePath);
                    }
                }
                zipOut.closeEntry();
            }
            zipOut.flush();
        }
    }

    public static void uncompress(InputStream in, String destPath) throws IOException {
        uncompress(in, destPath, Long.MAX_VALUE, Integer.MAX_VALUE);
    }

    public static void uncompress(InputStream in, String destPath,
                                  long maxBytes, int maxEntries) throws IOException {
        if (maxBytes < 0L || maxEntries < 0) {
            throw new IllegalArgumentException("ZIP limits must be non-negative");
        }
        try (ZipInputStream zipIn = new ZipInputStream(in)) {
            File destDir = new File(destPath).getCanonicalFile();
            if (!destDir.exists() && !destDir.mkdirs()) {
                throw new IOException("Failed to create directory: " + destDir);
            }
            String destDirPath = destDir.getPath() + File.separator;
            Set<String> extractedPaths = new HashSet<>();
            long totalBytes = 0L;
            int entryCount = 0;
            for (ZipEntry e; (e = zipIn.getNextEntry()) != null; ) {
                entryCount++;
                if (entryCount > maxEntries) {
                    throw new IOException("Too many ZIP entries: " + entryCount);
                }
                File file = new File(destDir, e.getName()).getCanonicalFile();
                // Zip Slip 防护：校验目标路径在解压目录之下
                if (!file.getPath().startsWith(destDirPath)) {
                    throw new IOException("Zip entry with path traversal: " + e.getName());
                }
                if (!extractedPaths.add(file.getPath())) {
                    throw new IOException("Duplicate ZIP entry: " + e.getName());
                }
                if (e.isDirectory()) {
                    if (!file.exists() && !file.mkdirs()) {
                        throw new IOException("Failed to create directory: " + file);
                    }
                    zipIn.closeEntry();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Failed to create directory: " + parent);
                }
                try (FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[4096];
                    for (int len; (len = zipIn.read(buffer)) != -1; ) {
                        totalBytes += len;
                        if (totalBytes > maxBytes) {
                            throw new IOException("ZIP content too large: " + totalBytes);
                        }
                        out.write(buffer, 0, len);
                    }
                }
                zipIn.closeEntry();
            }
        }
    }

}
