package com.kaisar.xposed.godmode.engine.util;

import android.util.Log;

import androidx.annotation.Keep;

/**
 * 全进程日志门面 — 所有 logcat 与持久化日志的统一入口。
 * <p>
 * 与 {@code control.GodModeLog}（system_server 落盘实现）互补而非重复：本类只做分发
 *（Writer 接口 + 串行日志线程 + 防递归），真正写 {@code godmodepro.log} 文件的是
 * system_server 侧的 GodModeLog；应用进程的 Writer 经 IPC 转发。DO NOT 绕过本类直接调用
 * {@code android.util.Log}（静态门禁强制），DO NOT 把落盘逻辑搬进本类。
 */
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
     * 实现方不需要自行处理异步，Logger 统一通过串行日志线程派发。
     */
    @FunctionalInterface
    public interface Writer {
        void write(int level, String tag, String msg, long timestamp);
    }

    private static volatile Writer sWriter;
    private static final ThreadLocal<Boolean> sDispatching =
            new ThreadLocal<>();

    /** 设置日志 Writer，供应用进程、管理端和 RuleServiceServer（system_server）调用。 */
    public static void setWriter(Writer writer) {
        sWriter = writer;
    }

    /**
     * 异步派发日志给 Writer。Writer 失败只能回退到 logcat，不能让宿主业务线程因为日志
     * 异常而崩溃，也不能在 Writer 内再次调用 Logger 造成递归。
     */
    private static void dispatch(int level, String tag, String msg) {
        Writer w = sWriter;
        if (w == null || Boolean.TRUE.equals(sDispatching.get())) return;
        long timestamp = System.currentTimeMillis();
        try {
            ThreadPools.LOG.execute(() -> {
                sDispatching.set(Boolean.TRUE);
                try {
                    w.write(level, tag, msg, timestamp);
                } catch (Throwable failure) {
                    Log.e(TAG, "log writer failed", failure);
                } finally {
                    sDispatching.remove();
                }
            });
        } catch (Throwable failure) {
            Log.e(TAG, "log dispatch failed", failure);
        }
    }

    // ===== 静态日志方法 =====

    public static int d(String tag, String msg) {
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.d(tag, msg);
        if (sWriter != null) dispatch(Log.DEBUG, tag, msg);
        return isLoggable(tag, Log.DEBUG) ? Log.d(tag, msg) : 0;
    }

    /** Debug logging with a throwable; avoids treating the throwable as a format argument. */
    public static int d(String tag, String msg, Throwable tr) {
        String full = msg + '\n' + Log.getStackTraceString(tr);
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.d(tag, msg, tr);
        if (sWriter != null) dispatch(Log.DEBUG, tag, full);
        return isLoggable(tag, Log.DEBUG) ? Log.d(tag, msg, tr) : 0;
    }

    public static int d(String tag, String format, Object... args) {
        boolean logcat = isLoggable(tag, Log.DEBUG);
        if (!logcat && sWriter == null) return 0;
        String msg = String.format(format, args);
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.d(tag, msg);
        if (sWriter != null) dispatch(Log.DEBUG, tag, msg);
        return logcat ? Log.d(tag, msg) : 0;
    }

    public static int i(String tag, String msg) {
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.i(tag, msg);
        if (sWriter != null) dispatch(Log.INFO, tag, msg);
        return isLoggable(tag, Log.INFO) ? Log.i(tag, msg) : 0;
    }

    public static int w(String tag, String msg) {
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.w(tag, msg);
        if (sWriter != null) dispatch(Log.WARN, tag, msg);
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg) : 0;
    }

    public static int w(String tag, String msg, Throwable tr) {
        String full = msg + '\n' + Log.getStackTraceString(tr);
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.w(tag, msg, tr);
        if (sWriter != null) dispatch(Log.WARN, tag, full);
        return isLoggable(tag, Log.WARN) ? Log.w(tag, msg, tr) : 0;
    }

    public static int e(String tag, String msg) {
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.e(tag, msg);
        if (sWriter != null) dispatch(Log.ERROR, tag, msg);
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg) : 0;
    }

    public static int e(String tag, String msg, Throwable tr) {
        String full = msg + '\n' + Log.getStackTraceString(tr);
        if (Boolean.TRUE.equals(sDispatching.get())) return Log.e(tag, msg, tr);
        if (sWriter != null) dispatch(Log.ERROR, tag, full);
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg, tr) : 0;
    }

    /**
     * Emits an error directly through the current Writer before returning. Intended only for
     * uncaught-exception paths that may terminate the process before the async queue drains.
     */
    public static int eImmediate(String tag, String msg, Throwable tr) {
        String full = msg + '\n' + Log.getStackTraceString(tr);
        Writer writer = sWriter;
        if (writer != null) {
            Boolean previous = sDispatching.get();
            sDispatching.set(Boolean.TRUE);
            try {
                writer.write(Log.ERROR, tag, full, System.currentTimeMillis());
            } catch (Throwable failure) {
                Log.e(TAG, "immediate log writer failed", failure);
            } finally {
                if (previous == null) sDispatching.remove();
                else sDispatching.set(previous);
            }
        }
        return isLoggable(tag, Log.ERROR) ? Log.e(tag, msg, tr) : 0;
    }

    public static String getStackTraceString(Throwable tr) {
        return Log.getStackTraceString(tr);
    }

    public static boolean isLoggable(String tag, int level) {
        try {
            return Log.isLoggable(TAG, level) || Log.isLoggable(tag, level);
        } catch (RuntimeException ignored) {
            // JVM Android stubs and a few restricted hosts do not implement logcat queries;
            // logging must remain non-fatal and the durable Writer path still stays available.
            return false;
        }
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
    public void d(String message, Throwable tr) { d(mName, message, tr); }
    public void i(String message) { i(mName, message); }
    public void w(String message) { w(mName, message); }
    public void w(String message, Throwable tr) { w(mName, message, tr); }
    public void e(String message) { e(mName, message); }
    public void e(String message, Throwable tr) { e(mName, message, tr); }
}
