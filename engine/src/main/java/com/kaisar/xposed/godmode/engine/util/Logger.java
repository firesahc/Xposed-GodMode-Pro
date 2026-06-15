package com.kaisar.xposed.godmode.engine.util;

import android.os.Process;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Keep
public final class Logger {

    private static final String TAG = "GodModePro";

    private static File sLogFile;
    private static long sMaxLogSize = 2 * 1024 * 1024; // 2MB
    private static int sMaxLogFiles = 3;
    /** 轮转锁，防止同一进程内 fileLog 回调重入时重复轮转 */
    private static final AtomicBoolean sRotating = new AtomicBoolean(false);
    private static final ExecutorService sLogExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Logger-File");
        t.setDaemon(true);
        return t;
    });
    private static final DateTimeFormatter sDateFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US);

    /**
     * 启用文件日志。每个进程按包名/进程名命名独立文件（godmodepro-{name}.log），
     * 避免多进程轮转冲突。目录和权限由首次写入的 fileLog() 自动处理。
     */
    public static void enableFileLog(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) return;
        sLogFile = new File(dirPath, "godmodepro-" + getProcessTag() + ".log");
    }

    /** 读取 /proc/self/cmdline 获取当前进程名（= 包名 / system_server） */
    private static String getProcessTag() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String name = r.readLine();
            if (name != null) {
                int nullIdx = name.indexOf('\0');
                if (nullIdx >= 0) name = name.substring(0, nullIdx);
                name = name.trim();
                if (!name.isEmpty()) return name;
            }
        } catch (IOException ignored) {}
        // 回退：读取失败时用 PID
        return String.valueOf(Process.myPid());
    }

    public static void disableFileLog() {
        sLogFile = null;
    }

    /**
     * 创建文件并设为仅 owner 可读写。目录保留世界可执行和可写（跨 UID 共享目录创建文件需要），
     * 但目录列表（读取）限制为 owner-only。文件本身仅 owner 可访问，
     * 因为每个进程只访问自己创建的日志文件。
     */
    private static void ensureWorldAccessible(File f) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
            parent.setReadable(true, true);   // 仅 owner 可读
            parent.setWritable(true, false);  // 世界可写（跨 UID 创建文件）
            parent.setExecutable(true, false); // 世界可执行（跨 UID 遍历）
        }
        if (!f.exists()) {
            f.createNewFile();
            f.setReadable(true, true);   // 仅 owner 可读
            f.setWritable(true, true);   // 仅 owner 可写
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

    /** 日志文件轮转 — 超过大小限制时自动滚动（AtomicBoolean 防重入） */
    private static void rotateLogFile() {
        if (!sRotating.compareAndSet(false, true)) return;
        try {
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
        } finally {
            sRotating.set(false);
        }
    }

    private static void fileLog(char level, String tag, String msg) {
        File f = sLogFile;
        if (f == null) return;
        sLogExecutor.execute(() -> {
            try {
                ensureWorldAccessible(f);
                rotateLogFile();
                try (OutputStreamWriter fw = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    fw.append(sDateFormat.format(LocalDateTime.now()))
                      .append(" ").append(level).append("/").append(tag)
                      .append(": ").append(msg).append("\n");
                }
            } catch (IOException e) {
                Log.w(TAG, "[Logger] Failed to write log to file: " + f.getAbsolutePath(), e);
            }
        });
    }

    public static int d(String tag, String msg) {
        if (sLogFile != null) fileLog('D', tag, msg);
        return isLoggable(tag, Log.DEBUG) ? Log.d(tag, msg) : 0;
    }

    public static int d(String tag, String format, Object... args) {
        boolean logcat = isLoggable(tag, Log.DEBUG);
        if (!logcat && sLogFile == null) return 0;
        String msg = String.format(format, args);
        if (sLogFile != null) fileLog('D', tag, msg);
        return logcat ? Log.d(tag, msg) : 0;
    }

    public static int i(String tag, String msg) {
        if (sLogFile != null) fileLog('I', tag, msg);
        return isLoggable(tag, Log.INFO) ? Log.i(tag, msg) : 0;
    }

    public static int w(String tag, String msg) {
        if (sLogFile != null) fileLog('W', tag, msg);
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg) : 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        if (sLogFile != null) fileLog('W', tag, msg + '\n' + Log.getStackTraceString(tr));
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg, tr) : 0;
    }

    public static int e(String tag, String msg) {
        if (sLogFile != null) fileLog('E', tag, msg);
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg) : 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        if (sLogFile != null) fileLog('E', tag, msg + '\n' + Log.getStackTraceString(tr));
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
