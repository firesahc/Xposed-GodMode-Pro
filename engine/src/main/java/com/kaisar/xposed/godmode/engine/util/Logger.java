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
    private static final ExecutorService sLogExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Logger-File");
        t.setDaemon(true);
        return t;
    });
    private static final SimpleDateFormat sDateFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    public static void enableFileLog(String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) return;
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "[Logger] Failed to create log dir: " + dirPath);
            return;
        }
        sLogFile = new File(dir, "godmodepro.log");
        Log.i(TAG, "[Logger] File logging enabled: " + sLogFile.getAbsolutePath());
    }

    public static void disableFileLog() {
        sLogFile = null;
    }

    private static void fileLog(char level, String tag, String msg) {
        File f = sLogFile;
        if (f == null) return;
        sLogExecutor.execute(() -> {
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
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
