package com.kaisar.xposed.godmode.control;

import android.util.Log;

import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 统一日志写入器 — 运行于 system_server 进程。
 * <p>
 * 从 {@code service/} 移入 control/ 包，职责不变。
 * 将所有日志写入 {@code /data/misc/godmode/godmodepro.log}。
 * <p>
 * 与 engine {@code Logger}（全进程门面）互补而非重复：本类仅是 {@code Logger.Writer}
 * 的 system_server 落盘实现（单线程 + 0600 权限 + 轮转 + 截断），不做分发与路由。
 * DO NOT 在本类之外另起落盘入口。
 */
public final class GodModeLog {

    private static final String TAG = "GodModeLog";
    private static final String LOG_FILE = GmConstants.DATA_DIR + "/godmodepro.log";
    private static final long MAX_FILE_SIZE = GmConstants.MAX_LOG_FILE_SIZE_BYTES;
    private static final int MAX_PENDING_RECORDS = 1024;
    /** 单条消息上限 — Binder 调用方不受信，防止单条记录主导格式化与轮转开销。 */
    private static final int MAX_MSG_CHARS = 64 * 1024;
    private static final int MAX_TAG_CHARS = 128;

    private static final DateTimeFormatter sFmt =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final ExecutorService sWriter = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_PENDING_RECORDS),
            r -> {
                Thread t = new Thread(r, "log-writer");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private GodModeLog() {}

    /**
     * 写入一条日志。
     */
    public static void write(int level, String packageName, String tag, String msg, long timestamp) {
        write(level, packageName, tag, msg, timestamp, android.os.Process.myPid());
    }

    /** Writes a record while retaining the PID of the process that produced it. */
    static void write(int level, String packageName, String tag, String msg,
                      long timestamp, int sourcePid) {
        String line = formatLine(level, packageName, tag, msg, timestamp, sourcePid);
        try {
            sWriter.execute(() -> doWrite(line));
        } catch (Throwable failure) {
            // The logger must never take down a Binder caller when its executor is unavailable.
            Log.e(TAG, "enqueue log failed", failure);
        }
    }

    /** Package-visible for deterministic JVM tests and for keeping the file contract explicit. */
    static String formatLine(int level, String packageName, String tag, String msg,
                             long timestamp, int sourcePid) {
        long eventTime = timestamp > 0L ? timestamp : System.currentTimeMillis();
        String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(eventTime),
                ZoneId.systemDefault()).format(sFmt);
        return String.format(Locale.US, "%s %d %s/%s: [%s] %s",
                time,
                sourcePid,
                levelTag(level),
                oneLine(truncate(tag, MAX_TAG_CHARS), "unknown"),
                oneLine(packageName, "unknown"),
                oneLine(truncate(msg, MAX_MSG_CHARS), ""));
    }

    /**
     * 防御性截断 — log() Binder 调用的 msg/tag 长度不受调用方约束，
     * 在唯一落盘入口统一收敛单条记录的格式化与内存开销。
     * 按 UTF-16 code unit 截断；极端情况下可能切断代理对，仅影响显示不影响解析。
     */
    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "…[truncated]";
    }

    private static String levelTag(int level) {
        switch (level) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG: return "D";
            case Log.INFO: return "I";
            case Log.WARN: return "W";
            case Log.ERROR: return "E";
            case Log.ASSERT: return "A";
            default: return "?";
        }
    }

    /** Keep one physical line per record so requestId/time-based parsing remains reliable. */
    private static String oneLine(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        return value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static void doWrite(String line) {
        File file = new File(LOG_FILE);
        try {
            ensureLogFile(file);
            if (file.length() >= MAX_FILE_SIZE) {
                rotateLog(file);
                ensureLogFile(file);
            }
            try (FileOutputStream fos = new FileOutputStream(file, true);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                 BufferedWriter writer = new BufferedWriter(osw)) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (Throwable failure) {
            Log.e(TAG, "write log failed", failure);
        }
    }

    private static void ensureLogFile(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()
                && (!parent.mkdirs() && !parent.isDirectory())) {
            throw new IOException("cannot create log directory: " + parent);
        }
        if (!file.exists() && (!file.createNewFile() && !file.isFile())) {
            throw new IOException("cannot create log file: " + file);
        }
        if (parent != null) {
            if (!setOwnerOnly(parent, true)) {
                throw new IOException("cannot restrict log directory permissions: " + parent);
            }
        }
        if (!setOwnerOnly(file, false)) {
            throw new IOException("cannot restrict log file permissions: " + file);
        }
    }

    private static boolean setOwnerOnly(File file, boolean executable) {
        // Clear pre-existing group/other bits first; the Java ownerOnly=true setters only add
        // the owner bit and otherwise preserve permissions inherited from an old installation.
        boolean cleared = file.setReadable(false, false)
                && file.setWritable(false, false)
                && file.setExecutable(false, false);
        if (!cleared) return false;
        return file.setReadable(true, true)
                && file.setWritable(true, true)
                && (!executable || file.setExecutable(true, true));
    }

    /** Called only by the single writer thread; rotation and append therefore stay ordered. */
    private static void rotateLog(File file) {
        File backup = new File(file.getAbsolutePath() + ".bak");
        if (backup.exists() && !backup.delete()) {
            Log.w(TAG, "rotate failed: cannot delete backup");
            return;
        }
        if (!file.renameTo(backup)) {
            Log.w(TAG, "rotate failed: cannot rename log");
        }
    }
}
