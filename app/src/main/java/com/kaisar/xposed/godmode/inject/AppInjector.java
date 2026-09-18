package com.kaisar.xposed.godmode.inject;

import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.ipc.ObserverCenter;
import com.kaisar.xposed.godmode.ipc.RuleServiceClient;
import com.kaisar.xposed.godmode.ipc.ServiceObserver;
import com.kaisar.xposed.godmode.orchestrator.RecyclerAdapterHook;
import com.kaisar.xposed.godmode.orchestrator.RecyclerBindingCoordinator;
import com.kaisar.xposed.godmode.orchestrator.RecyclerBindingSource;
import com.kaisar.xposed.godmode.orchestrator.RepeatableRuleGate;
import com.kaisar.xposed.godmode.orchestrator.RuleLifecycleManager;
import com.kaisar.xposed.godmode.orchestrator.RuleManager;

import com.kaisar.xposed.godmode.inject.hooks.ActivityResultHook;

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
        serviceClient.getLogBridge().installProcessLogging(packageName);
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
        assembleRecyclerBindingPort();
        assembleImagePickPort();
        if (!registerRuleLifecycleManager(packageName)) {
            return;
        }

        // [Phase 4] 初始化 RuleManager（Binder 获取规则 + 文件快照降级）
        RuleManager.init(packageName);
        serviceClient.addBinderDeathListener(() ->
                ModuleBootstrap.notifyEditModeChanged(false));

        // 注册 IPC 观察者，监听规则变更
        ObserverCenter.getDefault().addObserver(packageName, new ServiceObserver(
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
     * 将绑定端口适配器装配给运行时；先装 gate 再装 coordinator 最后装 port，
     * 失败只记日志不抛宿主。
     * <p>
     * 薄适配器转发静态钩子工具，运行时经端口调用，不直调静态方法；
     * 协调器持有运行时委托并承载全部 token/匹配逻辑，钩子回调经其分发。
     */
    private static void assembleRecyclerBindingPort() {
        try {
            RuleLifecycleManager lifecycle = RuleLifecycleManager.getInstance();
            RecyclerBindingCoordinator coordinator =
                    new RecyclerBindingCoordinator(lifecycle);
            try {
                RepeatableRuleGate gate = HookRegistry.getGate();
                if (gate != null) {
                    coordinator.setRepeatableRuleGate(gate);
                }
            } catch (Throwable failure) {
                Logger.w(TAG, "binding coordinator gate install failed", failure);
            }
            try {
                RecyclerAdapterHook.setBindingCoordinator(coordinator);
            } catch (Throwable failure) {
                Logger.w(TAG, "binding coordinator install failed for RecyclerAdapterHook",
                        failure);
            }
            RecyclerBindingSource source = new RecyclerBindingSource(lifecycle, coordinator);
            try {
                lifecycle.setRecyclerBindingPort(source);
            } catch (Throwable failure) {
                Logger.w(TAG, "binding port install failed for RuleLifecycleManager",
                        failure);
            }
        } catch (Throwable failure) {
            Logger.w(TAG, "binding port assembly failed", failure);
        }
    }

    /**
     * 将图片选择端口实现装配给编辑器面板；实例归属 Panel 持有，不新开全局单例，
     * 失败只记日志不抛宿主。
     * <p>
     * Panel 实例由 EditorOrchestrator 构造并持有（ModuleBootstrap 单例链路），
     * 故经 Orchestrator 的 getter 拿到同一实例做 setter 注入。
     */
    private static void assembleImagePickPort() {
        try {
            ActivityResultHook hook = new ActivityResultHook();
            try {
                ModuleBootstrap.getEditorOrchestrator().getPropertyEditor()
                        .setImagePickPort(hook);
            } catch (Throwable failure) {
                Logger.w(TAG, "image pick port install failed for PropertyEditorPanel",
                        failure);
            }
        } catch (Throwable failure) {
            Logger.w(TAG, "image pick port assembly failed", failure);
        }
    }

    /**
     * 注册生命周期订阅者到 EventBus；失败则跳过后续运行时初始化。
     * <p>
     * 注册顺序：EditorOrchestrator 先于 RuleLifecycleManager，保证 DESTROY 事件
     * 先清 Editor 会话再清规则缓存，与原“先 onActivityDestroyed 再 post DESTROY”
     * 顺序一致。EventBus 按注册顺序同步派发。
     *
     * @return true 注册成功，false 注册失败（调用方直接返回）
     */
    private static boolean registerRuleLifecycleManager(String packageName) {
        try {
            ModuleBootstrap.getEventBus().register(ModuleBootstrap.getEditorOrchestrator());
            ModuleBootstrap.getEventBus().register(RuleLifecycleManager.getInstance());
        } catch (Throwable failure) {
            Logger.w(TAG, "lifecycle subscriber registration failed", failure);
            Logger.e(TAG, "lifecycle hooks unavailable; skip runtime for "
                    + packageName);
            return false;
        }
        HookRegistry.setEventBusRegistered(true);
        return true;
    }
}
