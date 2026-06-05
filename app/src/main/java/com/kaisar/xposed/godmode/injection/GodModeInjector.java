package com.kaisar.xposed.godmode.injection;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XModuleResources;
import android.os.Binder;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.event.EditModeEvent;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.injection.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.injection.bridge.GodModeManager;
import com.kaisar.xposed.godmode.injection.bridge.ManagerObserver;
import com.kaisar.xposed.godmode.injection.hook.ActivityLifecycleHook;
import com.kaisar.xposed.godmode.injection.hook.DebugLayoutHookInstaller;
import com.kaisar.xposed.godmode.injection.hook.DispatchKeyEventHook;
import com.kaisar.xposed.godmode.injection.hook.EventHandlerHook;
import com.kaisar.xposed.godmode.injection.util.BlockListChecker;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.Property;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.service.GodModeManagerService;
import com.kaisar.xservicemanager.XServiceManager;

import java.io.File;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * GodMode 的 Xposed 入口。
 * <p>
 * 在 {@link IXposedHookZygoteInit} 阶段加载模块自身资源以便注入到目标应用。
 * 在 {@link IXposedHookLoadPackage} 阶段：
 * <ul>
 *   <li>对于 {@code "android"}（system_server）：通过剪贴板劫持将
 *       {@link GodModeManagerService} 注册为系统服务。</li>
 *   <li>对于目标应用：Hook Activity 生命周期、触摸事件、按键事件，
 *       并注册 IPC 观察者以接收规则变更。</li>
 * </ul>
 */
public final class GodModeInjector implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    // =========================================================================
    // 可观察状态 — 将编辑模式和规则变更传播到各个 Hook
    // =========================================================================

    // 初始化为安全默认值，防止在观察者回调到达前出现 null 拆箱 NPE。
    // Property 的 AtomicReference 默认为 null，所有读取方需能处理未初始化状态。
    public final static Property<Boolean> switchProp = new Property<>(false);
    public final static Property<ActRules> actRuleProp = new Property<>(new ActRules());
    public static XC_LoadPackage.LoadPackageParam loadPackageParam;

    // 双轨事件系统 — EventBus 与 Property 并行运行，逐步迁移监听方
    private static final EventBus sEventBus = EventBus.getDefault();

    private static State state = State.UNKNOWN;
    private static final DispatchKeyEventHook sDispatchKeyEventHook = new DispatchKeyEventHook();

    private enum State { UNKNOWN, ALLOWED, BLOCKED }

    // =========================================================================
    // 模块资源 — 在 initZygote 中加载，注入到目标应用的 AssetManager（委托 ModuleResources）
    // =========================================================================

    @Override
    public void initZygote(StartupParam startupParam) {
        ModuleResources.init(startupParam.modulePath,
                XModuleResources.createInstance(startupParam.modulePath, null));
    }

    // =========================================================================
    // 加载包 — 每个已加载应用的入口
    // =========================================================================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (R.string.res_inject_success >>> 24 == 0x7f) {
            XposedBridge.log("[GodModePro] package id must NOT be 0x7f, reject loading...");
            return;
        }
        if (!lpp.isFirstApplication) return;

        GodModeInjector.loadPackageParam = lpp;
        final String packageName = lpp.packageName;

        if ("android".equals(packageName)) {
            bootstrapSystemService();
        } else {
            injectIntoTargetApp(lpp, packageName);
        }
    }

    /** 在 system_server 内部将 GodModeManagerService 注册为系统服务 */
    private void bootstrapSystemService() {
        Logger.i(TAG, "[GodMode] inject GodModeManagerService as system service.");
        XServiceManager.initForSystemServer();
        XServiceManager.registerService("godmode",
                (XServiceManager.ServiceFetcher<Binder>) GodModeManagerService::new);
    }

    /** 向目标应用注入 Hook：Activity 生命周期、触摸、按键事件、IPC 观察者 */
    private void injectIntoTargetApp(XC_LoadPackage.LoadPackageParam lpp, String packageName) {
        Logger.i(TAG, "[GodMode] inject into app: " + packageName);

        // Hook Activity.onResume 以保持 mCurrentActivity 指向当前可见 Activity。
        // 仅跟踪 Activity.onCreate 不足——用户导航到子页面再返回后，
        // 原 Activity.onResume 触发但 mCurrentActivity 仍指向已销毁的 Activity，
        // 导致工具栏显示在错误的（或已销毁的）窗口上，buildViewNodes 返回零元素。
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sDispatchKeyEventHook.setActivity((Activity) param.thisObject);
            }
        });

        // Hook Activity.onCreate：注入模块资源，并在编辑模式已开启时延迟显示面板。
        // setActivity 移至 onResume hook，避免与 onResume 形成重复调用。
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                ModuleResources.injectInto(activity.getResources());
                if (switchProp.get()) {
                    // post 到 DecorView 以确保 setContentView 已完成、视图树完整后再显示面板。
                    // AppCompatActivity 在 super.onCreate() 返回之后才调用 setContentView()，
                    // 直接调用 buildViewNodes 会得到只有系统占位元素的残缺视图树。
                    activity.getWindow().getDecorView().post(() -> sDispatchKeyEventHook.setdisplay(true));
                }
                super.afterHookedMethod(param);
            }
        });

        registerHooks();
        registerObserver(packageName);
        Logger.d(TAG, "[GodMode] injection complete for: " + packageName);
    }

    /** 连接 ActivityLifecycleHook、EventHandlerHook、DispatchKeyEventHook 和调试布局 Hook */
    private void registerHooks() {
        Logger.d(TAG, "[GodMode] registering hooks...");
        // Activity 生命周期 Hook — 在 Activity 恢复/销毁时应用/撤销规则
        ActivityLifecycleHook lifecycleHook = new ActivityLifecycleHook();
        actRuleProp.addOnPropertyChangeListener(lifecycleHook);     // Property 路径
        sEventBus.register(lifecycleHook);                          // EventBus 路径
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", lifecycleHook);
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", lifecycleHook);

        // 调试布局 Hook — 编辑模式激活时显示视图边界
        DebugLayoutHookInstaller.install(switchProp);

        // 触摸事件 Hook — 拦截点击/拖拽以进行移除和修改操作
        EventHandlerHook eventHandlerHook = new EventHandlerHook();
        switchProp.addOnPropertyChangeListener(eventHandlerHook);
        XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                MotionEvent.class, eventHandlerHook);

        // 按键事件 Hook — 音量键切换节点选择器面板
        switchProp.addOnPropertyChangeListener(sDispatchKeyEventHook);
    }

    /** 注册 IPC 观察者，使服务端的规则更新能到达应用内 */
    private void registerObserver(String packageName) {
        GodModeManager gmManager = GodModeManager.getDefault();
        Logger.d(TAG, "[GodMode] registering observer for: " + packageName);
        // addObserver 立即通过 IPC 回调推送当前状态（onEditModeChanged + onViewRuleChanged），
        // 无需再手动设置 switchProp / actRuleProp。避免在 BLOCKED 应用中出现短暂的错误激活窗口。
        gmManager.addObserver(packageName, new ManagerObserver());
    }

    // =========================================================================
    // 公开通知方法 — 由 ManagerObserver 在规则/编辑模式变更时调用
    // =========================================================================

    public static void notifyEditModeChanged(boolean enable) {
        if (loadPackageParam == null) {
            Logger.w(TAG, "[GodMode] edit mode change ignored: loadPackageParam not ready");
            return;
        }
        if (state == State.UNKNOWN) {
            state = BlockListChecker.isBlocked(loadPackageParam.packageName)
                    ? State.BLOCKED : State.ALLOWED;
        }
        Logger.i(TAG, "[GodMode] edit mode " + enable + " state=" + state
                + " pkg=" + loadPackageParam.packageName);
        if (state == State.ALLOWED) {
            switchProp.set(enable);                        // 旧路径
            sEventBus.post(new EditModeEvent(enable));     // 新路径
        }
        sDispatchKeyEventHook.setdisplay(enable);
    }

    public static void notifyViewRulesChanged(ActRules actRules) {
        if (actRules == null) return;
        actRuleProp.set(actRules);                                    // 旧路径
        sEventBus.post(new RulesChangedEvent(                        // 新路径
                loadPackageParam != null ? loadPackageParam.packageName : "", actRules));
    }

    // 资源注入委托给 ModuleResources — 见 injectIntoTargetApp 中的 ModuleResources.injectInto() 调用
}
