package com.kaisar.xposed.godmode.service;

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
 * 无论是 system_server 自身的日志还是通过 IPC 转发的应用进程日志，
 * 都通过本类统一写入 {@code godmodepro.log}。
 * <p>
 * 格式：{@code "MM-dd HH:mm:ss.SSS I/[packageName]/tag: msg"}
 * <p>
 * 优化：持久化 OutputStream（只打开一次，不在每条日志上 open/close），
 * 大幅减少 IO 开销。
 */
final class GodModeLog {

    private static final String TAG = "GodModeLog";
    private static final File sLogFile = new File(GmConstants.DATA_DIR, "godmodepro.log");

    private static final long MAX_SIZE = GmConstants.MAX_LOG_FILE_SIZE_BYTES; // 2MB
    private static final int MAX_FILES = 3;

    private static final AtomicBoolean sRotating = new AtomicBoolean(false);
    private static volatile boolean sEnsured;
    private static volatile BufferedWriter sWriter;

    // 保持独立单线程池：GodModeLog 需要严格顺序写入 + 文件轮转原子性，共用线程池有乱序风险
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GodModeLog");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter sFmt =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.US);

    private GodModeLog() {}

    /**
     * 写入一条日志。
     *
     * @param level       android.util.Log 常量（DEBUG=3, INFO=4, WARN=5, ERROR=6）
     * @param packageName 来源包名（"system_server" 或应用包名）
     * @param tag         日志标签
     * @param msg         日志消息
     * @param timestamp   日志产生时间戳（由 Logger.dispatch 采集）
     */
    static void write(int level, String packageName, String tag,
                      String msg, long timestamp) {
        sExecutor.execute(() -> {
            try {
                if (!sEnsured) ensureReady();
                rotateIfNeeded();

                String line = sFmt.format(LocalDateTime.ofEpochSecond(
                                timestamp / 1000,
                                (int) ((timestamp % 1000) * 1_000_000),
                                java.time.ZoneOffset.ofHours(8)))
                        + " " + levelChar(level)
                        + "/[" + packageName + "]/" + tag + ": " + msg + "\n";

                sWriter.append(line);
                sWriter.flush();
            } catch (Exception e) {
                Log.w(TAG, "Failed to write: " + e.getMessage());
                // 写失败后强制重建 Writer，防止级联失败
                sEnsured = false;
                BufferedWriter old = sWriter;
                sWriter = null;
                if (old != null) {
                    try { old.close(); } catch (IOException ignored) {}
                }
            }
        });
    }

    /** 首次写入时初始化文件、目录和 Writer */
    private static void ensureReady() throws IOException {
        File parent = sLogFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
            parent.setReadable(true, true);
            parent.setWritable(true, false);
            parent.setExecutable(true, false);
        }
        if (!sLogFile.exists()) {
            sLogFile.createNewFile();
            sLogFile.setReadable(true, true);
            sLogFile.setWritable(true, true);
        }
        sWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(sLogFile, true),
                        StandardCharsets.UTF_8));
        sEnsured = true;
    }

    /** 重新打开 Writer（轮转后调用） */
    private static void reopen() throws IOException {
        BufferedWriter old = sWriter;
        if (old != null) {
            try { old.close(); } catch (IOException ignored) {}
        }
        sWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(sLogFile, true),
                        StandardCharsets.UTF_8));
    }

    private static void rotateIfNeeded() throws IOException {
        if (!sRotating.compareAndSet(false, true)) return;
        try {
            if (sWriter != null) sWriter.flush();
            if (!sLogFile.exists() || sLogFile.length() < MAX_SIZE) return;

            // 删除最旧文件
            File oldest = new File(sLogFile.getParent(), sLogFile.getName() + "." + MAX_FILES);
            if (oldest.exists()) oldest.delete();

            // 轮转 .2→.3, .1→.2
            for (int i = MAX_FILES - 1; i >= 1; i--) {
                File src = new File(sLogFile.getParent(), sLogFile.getName() + "." + i);
                if (src.exists()) {
                    File dst = new File(sLogFile.getParent(), sLogFile.getName() + "." + (i + 1));
                    src.renameTo(dst);
                }
            }

            // 关闭当前 Writer，重命名当前文件 → .1，重新打开
            if (sWriter != null) {
                try { sWriter.close(); } catch (IOException ignored) {}
                sWriter = null;
            }
            File rotated = new File(sLogFile.getParent(), sLogFile.getName() + ".1");
            if (!sLogFile.renameTo(rotated)) {
                Log.w(TAG, "Failed to rotate log file");
            }
            reopen();
        } finally {
            sRotating.set(false);
        }
    }

    private static char levelChar(int level) {
        switch (level) {
            case Log.DEBUG: return 'D';
            case Log.INFO:  return 'I';
            case Log.WARN:  return 'W';
            case Log.ERROR: return 'E';
            default:        return 'V';
        }
    }
}
