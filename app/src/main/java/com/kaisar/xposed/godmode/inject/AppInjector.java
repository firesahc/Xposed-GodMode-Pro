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
        // 设置日志 Writer：通过 IPC 转发到 system_server → GodModeLog → godmodepro.log
        Logger.setWriter((level, tag, msg, timestamp) -> {
            RuleServiceClient.getDefault().forwardLog(packageName, level, tag, msg, timestamp);
        });
        Logger.d(TAG, "[GodMode] inject into app: " + packageName);

        // 注册所有 Xposed Hook
        HookRegistry.registerAll(lpp, ModuleBootstrap.getSwitchProp());

        // [Phase 4] 初始化 RuleManager（Binder 获取规则 + 文件快照降级）
        RuleManager.init(packageName);

        // 注册 IPC 观察者，监听规则变更
        RuleServiceClient.getDefault().addObserver(packageName, new ServiceObserver(
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
