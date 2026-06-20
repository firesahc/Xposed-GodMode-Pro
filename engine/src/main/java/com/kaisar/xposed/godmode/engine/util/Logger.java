package com.kaisar.xposed.godmode.engine.util;

import android.util.Log;

import androidx.annotation.Keep;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Keep
public final class Logger {

    private static final String TAG = "GodModePro";

    // ===== Writer 接口 =====

    /**
     * 日志写入接口。
     * <ul>
     *   <li>system_server：实现为直接调用 {@code GodModeLog.write()}，写入 godmodepro.log</li>
     *   <li>应用进程：实现为 IPC 调用，将日志发送给 system_server 统一写入</li>
     * </ul>
     * 实现方负责自身的异步调度，Logger 在本线程同步调用 writer。
     */
    @FunctionalInterface
    public interface Writer {
        void write(int level, String tag, String msg, long timestamp);
    }

    private static volatile Writer sWriter;
    private static final ExecutorService sLogExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Logger-Writer");
        t.setDaemon(true);
        return t;
    });

    /** 设置日志 Writer，供 HookLauncher（应用进程）和 RuleServiceServer（system_server）调用 */
    public static void setWriter(Writer writer) {
        sWriter = writer;
    }

    /** 异步派发日志给 Writer */
    private static void dispatch(int level, String tag, String msg) {
        Writer w = sWriter;
        if (w == null) return;
        long timestamp = System.currentTimeMillis();
        sLogExecutor.execute(() -> {
            try {
                w.write(level, tag, msg, timestamp);
            } catch (Throwable ignored) {}
        });
    }

    // ===== 静态日志方法 =====

    public static int d(String tag, String msg) {
        if (sWriter != null) dispatch(Log.DEBUG, tag, msg);
        return isLoggable(tag, Log.DEBUG) ? Log.d(tag, msg) : 0;
    }

    public static int d(String tag, String format, Object... args) {
        boolean logcat = isLoggable(tag, Log.DEBUG);
        if (!logcat && sWriter == null) return 0;
        String msg = String.format(format, args);
        if (sWriter != null) dispatch(Log.DEBUG, tag, msg);
        return logcat ? Log.d(tag, msg) : 0;
    }

    public static int i(String tag, String msg) {
        if (sWriter != null) dispatch(Log.INFO, tag, msg);
        return isLoggable(tag, Log.INFO) ? Log.i(tag, msg) : 0;
    }

    public static int w(String tag, String msg) {
        if (sWriter != null) dispatch(Log.WARN, tag, msg);
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg) : 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        String full = msg + '\n' + Log.getStackTraceString(tr);
        if (sWriter != null) dispatch(Log.WARN, tag, full);
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg, tr) : 0;
    }

    public static int e(String tag, String msg) {
        if (sWriter != null) dispatch(Log.ERROR, tag, msg);
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg) : 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        String full = msg + '\n' + Log.getStackTraceString(tr);
        if (sWriter != null) dispatch(Log.ERROR, tag, full);
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg, tr) : 0;
    }

    public static String getStackTraceString(Throwable tr) {
        return Log.getStackTraceString(tr);
    }

    public static boolean isLoggable(String tag, int level) {
        return Log.isLoggable(TAG, level) || Log.isLoggable(tag, level);
    }

    // ===== 实例方法 =====

    public static Logger getLogger(String name) {
        return new Logger(name);
    }

    private final String mName;

    private Logger(String tag) {
        this.mName = tag;
    }

    public void d(String message) { d(mName, message); }
    public void i(String message) { i(mName, message); }
    public void w(String message, Throwable tr) { w(mName, message, tr); }
    public void e(String message, Throwable tr) { e(mName, message, tr); }
}
