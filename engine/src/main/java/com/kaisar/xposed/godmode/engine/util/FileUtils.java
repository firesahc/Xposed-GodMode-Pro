package com.kaisar.xposed.godmode.engine.util;

import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件 I/O 工具类 — 仅依赖平台核心 API（android.system / java.io），不依赖 app 模块。
 * 提供文件读写、删除、权限设置的原子操作。
 */
public final class FileUtils {

    private static final String TAG = "FileUtils";

    public static final int S_IRWXU = 00700;
    public static final int S_IRWXG = 00070;
    public static final int S_IRWXO = 00007;

    public static boolean copy(InputStream in, OutputStream out) {
        try {
            byte[] buffer = new byte[4096];
            for (int len; ((len = in.read(buffer)) != -1); ) {
                out.write(buffer, 0, len);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            Logger.w(TAG, "copy failed", e);
            return false;
        }
    }

    public static boolean delete(String filePath) {
        return delete(new File(filePath));
    }

    public static boolean delete(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File childFile : files) {
                    if (!delete(childFile)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    /**
     * Apply permission bits of given {@link File} via {@code chmod}.
     *
     * @param mode permission bits, e.g. {@link #S_IRWXU}
     * @return 0 on success, otherwise errno ({@code ENOENT} if the path does not exist).
     */
    public static int setPermissions(File path, int mode) {
        return setPermissions(path.getAbsolutePath(), mode);
    }

    /**
     * Apply permission bits of given path via {@code chmod}.
     *
     * @param mode permission bits, e.g. {@link #S_IRWXU}
     * @return 0 on success, otherwise errno ({@code ENOENT} if the path does not exist).
     */
    public static int setPermissions(String path, int mode) {
        try {
            Os.chmod(path, mode);
            return 0;
        } catch (ErrnoException e) {
            Logger.w(TAG, "chmod failed: " + path, e);
            return e.errno;
        }
    }

    public static String readTextFile(String filePath, int max, String ellipsis) throws IOException {
        return readTextFile(new File(filePath), max, ellipsis);
    }

    /**
     * Read a text file into a String, optionally limiting the length.
     *
     * @param file     to read (will not seek, so things like /proc files are OK)
     * @param max      length (positive for head, negative of tail, 0 for no limit)
     * @param ellipsis to add of the file was truncated (can be null)
     * @return the contents of the file, possibly truncated
     * @throws IOException if something goes wrong reading the file
     */
    public static String readTextFile(File file, int max, String ellipsis) throws IOException {
        try (InputStream input = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(input)) {
            long size = file.length();
            if (max > 0 || (size > 0 && max == 0)) {  // "head" mode: read the first N bytes
                if (size > 0 && (max == 0 || size < max)) max = (int) size;
                byte[] data = new byte[max + 1];
                int length = bis.read(data);
                if (length <= 0) return "";
                if (length <= max) return new String(data, 0, length);
                if (ellipsis == null) return new String(data, 0, max);
                return new String(data, 0, max) + ellipsis;
            } else if (max < 0) {  // "tail" mode: keep the last N
                int len;
                boolean rolled = false;
                byte[] last = null;
                byte[] data = null;
                do {
                    if (last != null) rolled = true;
                    byte[] tmp = last;
                    last = data;
                    data = tmp;
                    if (data == null) data = new byte[-max];
                    len = bis.read(data);
                } while (len == data.length);

                if (last == null && len <= 0) return "";
                if (last == null) return new String(data, 0, len);
                if (len > 0) {
                    rolled = true;
                    System.arraycopy(last, len, last, 0, last.length - len);
                    System.arraycopy(data, 0, last, last.length - len, len);
                }
                if (ellipsis == null || !rolled) return new String(last);
                return ellipsis + new String(last);
            } else {  // "cat" mode: size unknown, read it all in streaming fashion
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                int len;
                byte[] data = new byte[1024];
                do {
                    len = bis.read(data);
                    if (len > 0) contents.write(data, 0, len);
                } while (len == data.length);
                return contents.toString();
            }
        }
    }

    public static void stringToFile(File file, String string) throws IOException {
        stringToFile(file.getAbsolutePath(), string);
    }

    /**
     * Writes string to file. Basically same as "echo -n $string > $filename"
     */
    public static void stringToFile(String filename, String string) throws IOException {
        FileWriter out = new FileWriter(filename);
        try {
            out.write(string);
        } finally {
            out.close();
        }
    }
}
