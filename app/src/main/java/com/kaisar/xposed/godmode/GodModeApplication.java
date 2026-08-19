package com.kaisar.xposed.godmode;

import android.app.Application;
import android.content.Context;

import com.kaisar.xposed.godmode.ipc.RuleServiceClient;

/**
 * Created by jrsen on 17-10-16.
 */

public final class GodModeApplication extends Application {

    public static final String TAG = "GodModePro";
    private static GodModeApplication sApplication;

    public GodModeApplication() {
        sApplication = this;
    }

    @Override
    protected void attachBaseContext(Context base) {
        CrashHandler.install(base);
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // The settings process is not Xposed-injected, so it must install the same best-effort
        // IPC sink as injected target processes or its diagnostics disappear from the durable log.
        RuleServiceClient.getDefault().installProcessLogging(getPackageName());
    }

    public static GodModeApplication getApplication() {
        return sApplication;
    }

}
