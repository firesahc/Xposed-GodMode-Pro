package com.kaisar.xposed.godmode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Process;

import androidx.annotation.NonNull;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ui.SettingsActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Created by jrsen on 17-10-21.
 */

public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String LOG_FILE = "crash_info.log";
    private static final String RESTART_MARKER_FILE = "crash_restart.marker";
    private static final long RESTART_DELAY_MS = 1_000L;
    static final long RESTART_LOOP_WINDOW_MS = 30_000L;

    private final Context mContext;
    private final Thread.UncaughtExceptionHandler mPreviousHandler;

    static void install(Context context) {
        if (context == null) return;
        Thread.UncaughtExceptionHandler current =
                Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof CrashHandler) return;
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashHandler(context, current));
    }

    public static String getLastCrashInfo(Context context) {
        File logFile = getCrashLogFile(context);
        if (logFile == null) return null;
        if (logFile.exists()) {
            try {
                try (FileChannel fileChannel = new FileInputStream(logFile).getChannel()) {
                    long size = fileChannel.size();
                    if (size <= 0L || size > Integer.MAX_VALUE) return null;
                    ByteBuffer byteBuffer = ByteBuffer.allocate((int) size);
                    while (byteBuffer.hasRemaining()
                            && fileChannel.read(byteBuffer) >= 0) {
                        // Keep reading until EOF or the expected file size.
                    }
                    byteBuffer.flip();
                    return StandardCharsets.UTF_8.decode(byteBuffer).toString();
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Logger.e(TAG, "[CrashHandler] read crash info fail", e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
        }
        return null;
    }

    CrashHandler(Context context, Thread.UncaughtExceptionHandler previousHandler) {
        Context applicationContext = context.getApplicationContext();
        mContext = applicationContext != null ? applicationContext : context;
        mPreviousHandler = previousHandler;
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        try {
            recordCrash(t, e);
        } catch (Throwable recordFailure) {
            Logger.w(TAG, "crash recording failed", recordFailure);
        }
        try {
            scheduleRestartIfAllowed();
        } catch (Throwable restartFailure) {
            Logger.w(TAG, "crash restart scheduling failed", restartFailure);
        } finally {
            terminateThroughSystemHandler(t, e);
        }
    }

    private void recordCrash(Thread t, Throwable e) {
        try {
            File logFile = getCrashLogFile(mContext);
            if (logFile == null) throw new IOException("cache directory unavailable");
            try (FileChannel fileChannel = new FileOutputStream(logFile).getChannel()) {
                String stackTraceString = Logger.getStackTraceString(e);
                ByteBuffer buffer = ByteBuffer.wrap(
                        stackTraceString.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    fileChannel.write(buffer);
                }
            }
        } catch (IOException ioe) {
            Logger.w(TAG, "[CrashHandler] Failed to write crash log to file", ioe);
        }
        Logger.e(TAG, String.format("[CrashHandler] Crash on %s thread", t.getName()), e);
    }

    private void scheduleRestartIfAllowed() {
        File cacheDir = getCacheDirectory(mContext);
        if (cacheDir == null || !markRestartAttempt(
                new File(cacheDir, RESTART_MARKER_FILE),
                System.currentTimeMillis())) {
            Logger.w(TAG, "suppressing automatic restart after repeated crash");
            return;
        }
        Intent intent = new Intent(mContext, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("from_crash", true);
        PendingIntent restartIntent = PendingIntent.getActivity(mContext, 1, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager mgr = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        if (mgr != null) {
            mgr.set(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + RESTART_DELAY_MS, restartIntent);
        }
    }

    static synchronized boolean markRestartAttempt(File marker, long now) {
        if (marker == null || now < 0L) return false;
        long previous = marker.lastModified();
        if (previous > 0L
                && (now <= previous || now - previous < RESTART_LOOP_WINDOW_MS)) {
            return false;
        }
        try {
            File parent = marker.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            if (!marker.exists() && !marker.createNewFile()) return false;
            return marker.setLastModified(now);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private void terminateThroughSystemHandler(Thread thread, Throwable error) {
        if (mPreviousHandler != null && mPreviousHandler != this) {
            try {
                mPreviousHandler.uncaughtException(thread, error);
            } catch (Throwable handlerFailure) {
                Logger.w(TAG, "system crash handler failed", handlerFailure);
            }
        }
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private static File getCrashLogFile(Context context) {
        File cacheDir = getCacheDirectory(context);
        return cacheDir != null ? new File(cacheDir, LOG_FILE) : null;
    }

    private static File getCacheDirectory(Context context) {
        if (context == null) return null;
        File cacheDir = context.getCacheDir();
        return cacheDir != null ? cacheDir : context.getExternalCacheDir();
    }
}
