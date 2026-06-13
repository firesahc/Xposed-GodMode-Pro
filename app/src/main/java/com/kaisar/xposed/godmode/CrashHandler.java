package com.kaisar.xposed.godmode;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.kaisar.xposed.godmode.engine.util.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Created by jrsen on 17-10-21.
 */

public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String LOG_FILE = "crash_info.log";

    private final Context mContext;

    static void install(Context context) {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context));
    }

    public static String getLastCrashInfo(Context context) {
        File logFile = new File(context.getExternalCacheDir(), LOG_FILE);
        if (logFile.exists()) {
            try {
                try (FileChannel fileChannel = new FileInputStream(logFile).getChannel()) {
                    ByteBuffer byteBuffer = ByteBuffer.allocate((int) fileChannel.size());
                    fileChannel.read(byteBuffer);
                    byteBuffer.flip();
                    return new String(byteBuffer.array());
                }
            } catch (IOException e) {
                Logger.e(TAG, "[CrashHandler] read crash info fail", e);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                logFile.delete();
            }
        }
        return null;
    }

    CrashHandler(Context context) {
        mContext = context;
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        recordCrash(t, e);
        restartSelf();
    }

    private void recordCrash(Thread t, Throwable e) {
        try {
            File logFile = new File(mContext.getExternalCacheDir(), LOG_FILE);
            try (FileChannel fileChannel = new FileOutputStream(logFile).getChannel()) {
                String stackTraceString = Logger.getStackTraceString(e);
                fileChannel.write(ByteBuffer.wrap(stackTraceString.getBytes()));
            }
        } catch (IOException ioe) {
            Logger.w(TAG, "[CrashHandler] Failed to write crash log to file", ioe);
        }
        Logger.e(TAG, String.format("[CrashHandler] Crash on %s thread", t.getName()), e);
    }


    private void restartSelf() {
        Intent intent = new Intent(mContext, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("from_crash", true);
        PendingIntent restartIntent = PendingIntent.getActivity(mContext, 1, intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager mgr = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        if (mgr != null) {
            // 使用 setExactAndAllowWhileIdle 确保在 Android 14+ 上也能可靠触发
            // （低于 API 19 的设备降级到普通 set）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                mgr.setExactAndAllowWhileIdle(AlarmManager.RTC,
                        System.currentTimeMillis() + 100, restartIntent);
            } else {
                mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, restartIntent);
            }
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

}
