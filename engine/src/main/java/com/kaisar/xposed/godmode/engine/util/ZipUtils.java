package com.kaisar.xposed.godmode.engine.util;

import com.kaisar.xposed.godmode.engine.util.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        try (ZipInputStream zipIn = new ZipInputStream(in)) {
            File destDir = new File(destPath).getCanonicalFile();
            for (ZipEntry e; (e = zipIn.getNextEntry()) != null; ) {
                File file = new File(destDir, e.getName()).getCanonicalFile();
                // Zip Slip 防护：校验目标路径在解压目录之下
                if (!file.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("Zip entry with path traversal: " + e.getName());
                }
                try (FileOutputStream out = new FileOutputStream(file)) {
                    if (!FileUtils.copy(zipIn, out)) {
                        throw new IOException("Failed to decompress entry: " + e.getName());
                    }
                }
                zipIn.closeEntry();
            }
        }
    }

}
