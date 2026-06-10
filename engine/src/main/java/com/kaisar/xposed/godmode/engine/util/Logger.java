package com.kaisar.xposed.godmode.engine.util;

import android.util.Log;

import androidx.annotation.Keep;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Keep
public final class Logger {

    private static final String TAG = "GodModePro";

    private static File sLogFile;
    private static long sMaxLogSize = 2 * 1024 * 1024; // 2MB
    private static int sMaxLogFiles = 3;
    private static final ExecutorService sLogExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Logger-File");
        t.setDaemon(true);
        return t;
    });
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * 启用文件日志。所有进程指向同一路径时共享同一个日志文件。
     * 目录和权限由首次写入的 fileLog() 自动处理，多进程通过 O_APPEND 安全并发写入。
     */
    public static void enableFileLog(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) return;
        sLogFile = new File(dirPath, "godmodepro.log");
    }

    public static void disableFileLog() {
        sLogFile = null;
    }

    /** 创建文件并设为世界可读写（多进程共享），创建目录并设为世界可执行（跨进程访问）。*/
    private static void ensureWorldAccessible(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
            parent.setReadable(true, false);
            parent.setWritable(true, false);
            parent.setExecutable(true, false);
        }
        if (!f.exists()) {
            f.createNewFile();
            f.setReadable(true, false);
            f.setWritable(true, false);
        }
    }

    /**
     * 设置文件日志轮转参数。
     * @param maxSize  单个日志文件最大字节数（默认 2MB）
     * @param maxFiles 保留的轮转文件个数（默认 3）
     */
    public static void setRotationPolicy(long maxSize, int maxFiles) {
        if (maxSize > 0) sMaxLogSize = maxSize;
        if (maxFiles > 0) sMaxLogFiles = maxFiles;
    }

    /** 日志文件轮转 — 超过大小限制时自动滚动 */
    private static void rotateLogFile() {
        File f = sLogFile;
        if (f == null || !f.exists() || f.length() < sMaxLogSize) return;
        // 删除最旧的文件
        File oldest = new File(f.getParent(), f.getName() + "." + sMaxLogFiles);
        if (oldest.exists() && !oldest.delete()) {
            Log.w(TAG, "[Logger] Failed to delete oldest log backup");
        }
        // 依次重命名 .2→.3, .1→.2
        for (int i = sMaxLogFiles - 1; i >= 1; i--) {
            File src = new File(f.getParent(), f.getName() + "." + i);
            if (src.exists()) {
                File dst = new File(f.getParent(), f.getName() + "." + (i + 1));
                src.renameTo(dst);
            }
        }
        // 当前日志 → .1
        File rotated = new File(f.getParent(), f.getName() + ".1");
        if (!f.renameTo(rotated)) {
            Log.w(TAG, "[Logger] Failed to rotate log file");
        }
    }

    private static void fileLog(char level, String tag, String msg) {
        File f = sLogFile;
        if (f == null) return;
        sLogExecutor.execute(() -> {
            try {
                ensureWorldAccessible(f);
                rotateLogFile();
                try (FileWriter fw = new FileWriter(f, true)) {
                    fw.append(sDateFormat.format(new Date()))
                      .append(" ").append(level).append("/").append(tag)
                      .append(": ").append(msg).append("\n");
                }
            } catch (IOException e) {
                Log.w(TAG, "[Logger] Failed to write log to file: " + f.getAbsolutePath(), e);
            }
        });
    }

    public static int d(String tag, String msg) {
        fileLog('D', tag, msg);
        return isLoggable(tag, Log.DEBUG) ? Log.d(tag, msg) : 0;
    }

    public static int d(String tag, String format, Object... args) {
        String msg = String.format(format, args);
        fileLog('D', tag, msg);
        return isLoggable(tag, Log.DEBUG) ? Log.d(tag, msg) : 0;
    }

    public static int i(String tag, String msg) {
        fileLog('I', tag, msg);
        return isLoggable(tag, Log.INFO) ? Log.i(tag, msg) : 0;
    }

    public static int w(String tag, String msg) {
        fileLog('W', tag, msg);
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg) : 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        fileLog('W', tag, msg + '\n' + Log.getStackTraceString(tr));
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg, tr) : 0;
    }

    public static int e(String tag, String msg) {
        fileLog('E', tag, msg);
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg) : 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        fileLog('E', tag, msg + '\n' + Log.getStackTraceString(tr));
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg, tr) : 0;
    }

    public static String getStackTraceString(Throwable tr) {
        return Log.getStackTraceString(tr);
    }

    public static boolean isLoggable(String tag, int level) {
        return Log.isLoggable(TAG, level) || Log.isLoggable(tag, level);
    }

    public static Logger getLogger(String name) {
        return new Logger(name);
    }

    private final String mName;

    private Logger(String tag) {
        this.mName = tag;
    }

    public void d(String message) {
        d(mName, message);
    }

    public void i(String message) {
        i(mName, message);
    }

    public void w(String message, Throwable tr) {
        w(mName, message, tr);
    }

    public void e(String message, Throwable tr) {
        e(mName, message, tr);
    }
}
