package com.kaisar.xposed.godmode.inject;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.ipc.ServiceObserver;
import com.kaisar.xposed.godmode.orchestrator.RecyclerAdapterHook;
import com.kaisar.xposed.godmode.orchestrator.RepeatableRuleGate;
import com.kaisar.xposed.godmode.orchestrator.RuleLifecycleManager;
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

        // [P0-1] gate 组装 + EventBus 注册（原 HookRegistry 内聚逻辑外移，打破双向循环）。
        assembleRepeatableGate();
        if (!registerRuleLifecycleManager(packageName)) {
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

    /** 将 inject 层门控实现装配给 orchestrator 层；失败只记日志不抛给宿主。 */
    private static void assembleRepeatableGate() {
        try {
            RepeatableRuleGate gate = HookRegistry.getGate();
            try {
                RuleLifecycleManager.getInstance().setRepeatableRuleGate(gate);
            } catch (Throwable failure) {
                Logger.w(TAG, "repeatable gate install failed for RuleLifecycleManager",
                        failure);
            }
            try {
                RecyclerAdapterHook.setRepeatableRuleGate(gate);
            } catch (Throwable failure) {
                Logger.w(TAG, "repeatable gate install failed for RecyclerAdapterHook",
                        failure);
            }
        } catch (Throwable failure) {
            Logger.w(TAG, "repeatable gate assembly failed", failure);
        }
    }

    /**
     * 注册 RuleLifecycleManager 到 EventBus；失败则跳过后续运行时初始化。
     *
     * @return true 注册成功，false 注册失败（调用方直接返回）
     */
    private static boolean registerRuleLifecycleManager(String packageName) {
        try {
            ModuleBootstrap.getEventBus().register(RuleLifecycleManager.getInstance());
        } catch (Throwable failure) {
            Logger.w(TAG, "RuleLifecycleManager registration failed", failure);
            Logger.e(TAG, "lifecycle hooks unavailable; skip runtime for "
                    + packageName);
            return false;
        }
        HookRegistry.setEventBusRegistered(true);
        return true;
    }
}
