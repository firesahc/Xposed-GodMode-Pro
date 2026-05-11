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

    public final static Property<Boolean> switchProp = new Property<>();
    public final static Property<ActRules> actRuleProp = new Property<>();
    public static XC_LoadPackage.LoadPackageParam loadPackageParam;

    private static State state = State.UNKNOWN;
    private static final DispatchKeyEventHook sDispatchKeyEventHook = new DispatchKeyEventHook();

    private enum State { UNKNOWN, ALLOWED, BLOCKED }

    // =========================================================================
    // 模块资源 — 在 initZygote 中加载，注入到目标应用的 AssetManager
    // =========================================================================

    private static String modulePath;
    private static Resources moduleRes;

    @Override
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
        moduleRes = XModuleResources.createInstance(modulePath, null);
        GmResources.init(moduleRes);
    }

    // =========================================================================
    // 加载包 — 每个已加载应用的入口
    // =========================================================================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (R.string.res_inject_success >>> 24 == 0x7f) {
            XposedBridge.log("[GodMode] package id must NOT be 0x7f, reject loading...");
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

        // Hook Activity.onCreate 以跟踪 Activity 并注入模块资源
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                sDispatchKeyEventHook.setactivity(activity);
                injectModuleResources(activity.getResources());
                if (switchProp.get()) {
                    sDispatchKeyEventHook.setdisplay(true);
                }
                super.afterHookedMethod(param);
            }
        });

        registerHooks();
        registerObserver(packageName);
    }

    /** 连接 ActivityLifecycleHook、EventHandlerHook、DispatchKeyEventHook 和调试布局 Hook */
    private void registerHooks() {
        // Activity 生命周期 Hook — 在 Activity 恢复/销毁时应用/撤销规则
        ActivityLifecycleHook lifecycleHook = new ActivityLifecycleHook();
        actRuleProp.addOnPropertyChangeListener(lifecycleHook);
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
        gmManager.addObserver(packageName, new ManagerObserver());
        switchProp.set(gmManager.isInEditMode());
        actRuleProp.set(gmManager.getRules(packageName));
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
            switchProp.set(enable);
        }
        sDispatchKeyEventHook.setdisplay(enable);
    }

    public static void notifyViewRulesChanged(ActRules actRules) {
        actRuleProp.set(actRules);
    }

    // =========================================================================
    // 资源注入 — 将模块资源注入目标应用的 AssetManager
    // =========================================================================

    /**
     * 将 GodMode 模块资源注入目标应用的 {@link Resources}。
     * 使得在目标应用中渲染覆盖层 UI 时可以使用模块的布局、字符串和图片资源。
     */
    public static void injectModuleResources(Resources res) {
        if (res == null) return;
        try {
            res.getString(R.string.res_inject_success);
            return; // 已注入，无需重复
        } catch (Resources.NotFoundException ignored) {
        }
        try {
            String path = modulePath;
            if (path == null) {
                throw new RuntimeException("get module path failed, loader="
                        + GodModeInjector.class.getClassLoader());
            }
            AssetManager assets = res.getAssets();
            @SuppressLint("DiscouragedPrivateApi")
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            int cookie = (int) addAssetPath.invoke(assets, path);

            try {
                Logger.i(TAG, "injectModuleResources: " + res.getString(R.string.res_inject_success));
            } catch (Resources.NotFoundException e) {
                File f = new File(path);
                Logger.e(TAG, "Fatal: injectModuleResources: test injection failure!");
                Logger.e(TAG, "injectModuleResources: cookie=" + cookie + ", path=" + path
                        + ", loader=" + GodModeInjector.class.getClassLoader());
                Logger.e(TAG, "sModulePath: exists=" + f.exists()
                        + ", isDirectory=" + f.isDirectory()
                        + ", canRead=" + f.canRead()
                        + ", fileLength=" + f.length());
            }
        } catch (Exception e) {
            Logger.e(TAG, "Inject module resources error", e);
        }
    }
}
