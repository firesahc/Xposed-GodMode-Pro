package com.kaisar.xposed.godmode.inject;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.ipc.ServiceObserver;
import com.kaisar.xposed.godmode.orchestrator.RuleManager;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 目标应用注入器 — 为应用的每个进程初始化规则运行时并注册 Hook。
 * <p>
 * 由 {@link ModuleBootstrap} 在普通应用包加载时调用。
 */
public final class AppInjector {

    private static final String TAG = "AppInjector";

    /** 注入目标应用 */
    public void inject(XC_LoadPackage.LoadPackageParam lpp, String packageName) {
        RuleServiceClient serviceClient = RuleServiceClient.getDefault();
        // Install the sink before the handshake so failures during startup use the same
        // contract as later runtime logs. forwardLog remains best effort until Binder is ready.
        serviceClient.installProcessLogging(packageName);
        if (!serviceClient.awaitReady(2_500L)) {
            Logger.e(TAG, "IPC handshake failed; skip hooks for " + packageName
                    + ", state=" + serviceClient.getServiceState());
            return;
        }

        Logger.d(TAG, "inject into app: " + packageName);

        // 注册所有 Xposed Hook
        HookRegistry.HookInstallReport hookReport = HookRegistry.registerAll(
                ModuleBootstrap.getSwitchProp());
        if (!hookReport.coreReady) {
            Logger.e(TAG, "lifecycle hooks unavailable; skip runtime for "
                    + packageName);
            return;
        }

        // [Phase 4] 初始化 RuleManager（Binder 获取规则 + 文件快照降级）
        RuleManager.init(packageName);
        serviceClient.addBinderDeathListener(() ->
                ModuleBootstrap.notifyEditModeChanged(false));

        // 注册 IPC 观察者，监听规则变更
        serviceClient.addObserver(packageName, new ServiceObserver(
                new ServiceObserver.Callback() {
                    @Override
                    public void onEditModeChanged(boolean enable) {
                        ModuleBootstrap.notifyEditModeChanged(enable);
                    }

                    @Override
                    public void onViewRulesChanged(com.kaisar.xposed.godmode.rule.ActRules rules) {
                        if (RuleManager.isInitialized()) {
                            RuleManager.get().acceptServiceSnapshot(rules);
                        }
                    }
                }));
    }
}
