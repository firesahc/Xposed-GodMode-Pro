package com.kaisar.xposed.godmode.control;

import android.util.Log;

import com.kaisar.xposed.godmode.engine.util.GmConstants;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一日志写入器 — 运行于 system_server 进程。
 * <p>
 * 从 {@code service/} 移入 control/ 包，职责不变。
 * 将所有日志写入 {@code /data/misc/godmode/godmodepro.log}。
 */
public final class GodModeLog {

    private static final String TAG = "GodModeLog";
    private static final String LOG_FILE = GmConstants.DATA_DIR + "/godmodepro.log";
    private static final long MAX_FILE_SIZE = GmConstants.MAX_LOG_FILE_SIZE_BYTES;

    private static final DateTimeFormatter sFmt =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final ExecutorService sWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "log-writer");
        t.setDaemon(true);
        return t;
    });

    /** 日志级别标签 */
    private static final String[] LEVEL_TAGS = {"", "", "", "", "", "V", "D", "I", "W", "E"};

    private static final AtomicBoolean sRotating = new AtomicBoolean(false);

    private GodModeLog() {}

    /**
     * 写入一条日志。
     */
    public static void write(int level, String packageName, String tag, String msg, long timestamp) {
        String line = String.format(Locale.US, "%s %d %s/%s: [%s] %s",
                LocalDateTime.now().format(sFmt),
                android.os.Process.myPid(),
                LEVEL_TAGS[Math.min(level, LEVEL_TAGS.length - 1)],
                tag,
                packageName,
                msg);
        sWriter.execute(() -> doWrite(line));
    }

    private static synchronized void doWrite(String line) {
        File file = new File(LOG_FILE);
        if (file.length() > MAX_FILE_SIZE && !sRotating.get()) {
            rotateLog(file);
        }
        try (FileOutputStream fos = new FileOutputStream(file, true);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "write log failed: " + e.getMessage());
        }
    }

    private static void rotateLog(File file) {
        if (!sRotating.compareAndSet(false, true)) return;
        sWriter.execute(() -> {
            try {
                File backup = new File(file.getAbsolutePath() + ".bak");
                if (backup.exists() && !backup.delete()) {
                    Log.w(TAG, "rotate: failed to delete old backup");
                }
                if (!file.renameTo(backup)) {
                    Log.w(TAG, "rotate: rename failed");
                }
            } finally {
                sRotating.set(false);
            }
        });
    }
}
