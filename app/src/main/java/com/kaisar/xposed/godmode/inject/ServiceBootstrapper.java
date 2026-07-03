package com.kaisar.xposed.godmode.inject;

import android.os.Binder;

import com.kaisar.xposed.godmode.control.RuleServiceServer;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xservicemanager.XServiceManager;

/**
 * SystemServer 服务注入器 — 向 system_server 注册 {@link RuleServiceServer}。
 * <p>
 * 由 {@link ModuleBootstrap} 在 "android" 包加载时调用。
 */
public final class ServiceBootstrapper {

    private static final String TAG = "ServiceBootstrapper";

    private ServiceBootstrapper() {}

    /** 注入 RuleServiceServer 为系统级 Service */
    public static void bootstrap() {
        Logger.i(TAG, "[GodMode] inject RuleServiceServer as system service.");

        XServiceManager.setLogDelegate(new XServiceManager.LogDelegate() {
            @Override public void d(String tag, String msg) { Logger.d(tag, msg); }
            @Override public void i(String tag, String msg) { Logger.i(tag, msg); }
            @Override public void w(String tag, String msg) { Logger.w(tag, msg); }
            @Override public void w(String tag, String msg, Throwable tr) { Logger.w(tag, msg, tr); }
            @Override public void e(String tag, String msg) { Logger.e(tag, msg); }
            @Override public void e(String tag, String msg, Throwable tr) { Logger.e(tag, msg, tr); }
        });

        boolean bridgeInstalled = XServiceManager.initForSystemServer();
        if (!bridgeInstalled) {
            Logger.e(TAG, "[GodMode] XServiceManager bridge init failed: "
                    + XServiceManager.getLastError());
        }

        XServiceManager.registerService("godmode",
                (XServiceManager.ServiceFetcher<Binder>) RuleServiceServer::new);
        XServiceManager.flushRegisteredServices();
    }
}
