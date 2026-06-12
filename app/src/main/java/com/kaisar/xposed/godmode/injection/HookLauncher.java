package com.kaisar.xposed.godmode.injection;

import android.app.Activity;
import android.content.res.XModuleResources;
import android.os.Binder;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.bridge.RuleServiceClient;
import com.kaisar.xposed.godmode.injection.bridge.ServiceObserver;
import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;
import com.kaisar.xposed.godmode.injection.entry.ActivityKeyHook;
import com.kaisar.xposed.godmode.injection.entry.DebugLayoutHook;
import com.kaisar.xposed.godmode.injection.entry.TouchHook;
import com.kaisar.xposed.godmode.injection.util.BlockListChecker;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.service.RuleServiceServer;
import com.kaisar.xservicemanager.XServiceManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * GodMode 的 Xposed 入口点。
 * <p>
 * 实现 {@link IXposedHookZygoteInit} 用于在 Zygote 初始化阶段准备模块资源，
 * 实现 {@link IXposedHookLoadPackage} 用于拦截目标应用：
 * <ul>
 *   <li>当包名为 {@code "android"} 时，注入 system_server 进程，
 *       注册 {@link RuleServiceServer} 作为系统级 Service</li>
 *   <li>当包名为普通应用时，注入目标 app 进程，Hook Activity 生命周期、
 *       触摸事件和按键事件，通过 IPC 与系统服务通信</li>
 * </ul>
 */
public final class HookLauncher implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "HookLauncher";

    // =========================================================================
    // 字段定义 — 开关状态、注入参数、编辑器和 Hook
    // =========================================================================

    // 注意：所有字段访问前必须判空，避免在模块未初始化时触发 NPE
    // Property 使用 AtomicReference 确保线程安全，初始值为 null 直到注入完成
    public final static Property<Boolean> switchProp = new Property<>(false);
    public static volatile XC_LoadPackage.LoadPackageParam loadPackageParam;

    // EventBus — 仅用于规则变更通知（RulesChangedEvent），编辑模式通过 Property 分发
    private static final EventBus sEventBus = EventBus.getDefault();

    private static volatile State state = State.UNKNOWN;
    private static final EditorOrchestrator sEditorOrchestrator = new EditorOrchestrator(switchProp);

    /** 获取编辑器编排器实例（EditorOrchestrator 单例）*/
    public static EditorOrchestrator getEditorOrchestrator() { return sEditorOrchestrator; }

    private enum State { UNKNOWN, ALLOWED, BLOCKED }

    // =========================================================================
    // Zygote 初始化 — initZygote 阶段准备模块资源（AssetManager 和 ModuleResources）
    // =========================================================================

    @Override
    public void initZygote(StartupParam startupParam) {
        ModuleResources.init(startupParam.modulePath,
                XModuleResources.createInstance(startupParam.modulePath, null));
    }

    // =========================================================================
    // 处理加载包 — 根据包名分流到系统服务注入或目标应用注入
    // =========================================================================

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (R.string.res_inject_success >>> 24 == 0x7f) {
            Logger.e(TAG, "package id must NOT be 0x7f, reject loading...");
            return;
        }
        if (!lpp.isFirstApplication) {
            if ("android".equals(lpp.packageName)) {
                Logger.w(TAG, "handleLoadPackage(android) skipped — isFirstApplication=false,"
                        + " system_server init requires first application flag");
            }
            return;
        }

        HookLauncher.loadPackageParam = lpp;
        final String packageName = lpp.packageName;

        if ("android".equals(packageName)) {
            bootstrapSystemService();
        } else {
            injectIntoTargetApp(lpp, packageName);
        }
    }

    /** 向 system_server 注入 RuleServiceServer 作为系统级 Service */
    private void bootstrapSystemService() {
        Logger.i(TAG, "[GodMode] inject RuleServiceServer as system service.");
        XServiceManager.setLogDelegate(new XServiceManager.LogDelegate() {
            @Override public void d(String tag, String msg) { Logger.d(tag, msg); }
            @Override public void i(String tag, String msg) { Logger.i(tag, msg); }
            @Override public void w(String tag, String msg) { Logger.w(tag, msg); }
            @Override public void w(String tag, String msg, Throwable tr) { Logger.w(tag, msg, tr); }
            @Override public void e(String tag, String msg) { Logger.w(tag, msg); }
            @Override public void e(String tag, String msg, Throwable tr) { Logger.e(tag, msg, tr); }
        });
        XServiceManager.initForSystemServer();
        XServiceManager.registerService("godmode",
                (XServiceManager.ServiceFetcher<Binder>) RuleServiceServer::new);
        XServiceManager.flushRegisteredServices();
    }

    /** 注入目标应用 — Hook Activity 生命周期、触摸事件和按键事件，通过 IPC 与系统服务通信 */
    private void injectIntoTargetApp(XC_LoadPackage.LoadPackageParam lpp, String packageName) {
        // 所有进程共享同一日志文件（O_APPEND 并发安全）
        Logger.enableFileLog(android.os.Environment.getDataDirectory().getAbsolutePath() + "/misc/godmode");
        Logger.i(TAG, "[GodMode] inject into app: " + packageName);
        hookActivityOnResume();
        hookActivityOnCreate();
        registerHooks();
        registerObserver(packageName);
        Logger.d(TAG, "[GodMode] injection complete for: " + packageName);
    }

    /** Hook Activity.onResume 用于记录 mCurrentActivity 引用，方便后续获取当前 Activity */
    private static void hookActivityOnResume() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sEditorOrchestrator.setActivity((Activity) param.thisObject);
            }
        });
    }

    /** Hook Activity.onCreate 用于注入模块资源，并在编辑器模式下显示编辑面板 */
    private static void hookActivityOnCreate() {
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                ModuleResources.injectInto(activity.getResources());
                if (switchProp.get()) {
                    // post 闂?DecorView 缂佺虎鍙庨崰鏇犳崲?setContentView 閻庣懓鎲¤ぐ鍐偩椤掑嫬绠ｉ柟鎵虫杹閸嬫挻鎷呯粵瀣秺闂佹悶鍎抽崑鎾绘偉閼碱兘鍋撻悷鐗堟拱闁哄棴缍佸畷銉︽償閳ヨ櫕娅冮梺鍝勫婢т粙濡靛鑸殿棃闁靛繆鍓濈欢?
                    activity.getWindow().getDecorView().post(() -> sEditorOrchestrator.setDisplay(true));
                }
                super.afterHookedMethod(param);
            }
        });
    }

    /** 注册各类 Hook — 生命周期观察者、调试布局、触摸事件和按键事件 */
    private void registerHooks() {
        Logger.d(TAG, "[GodMode] registering hooks...");
        // Activity 生命周期 Hook — 监听 Activity 的 onPostResume 和 onDestroy 事件，
        // 由 LifecycleObserver 统一处理，通过 EventBus 接收规则变更通知
        LifecycleObserver lifecycleObserver = new LifecycleObserver();
        sEventBus.register(lifecycleObserver);                          // EventBus 注册观察者
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", lifecycleObserver);
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", lifecycleObserver);

        // 调试布局模式 Hook — 用于显示控件边界信息，由开关属性控制
        DebugLayoutHook.install(switchProp);

        // 触摸事件 Hook — 用于拦截触摸操作，实现选择/移除/修改交互
        TouchHook touchHook = new TouchHook(sEditorOrchestrator);
        switchProp.addOnPropertyChangeListener(sEditorOrchestrator);
        XposedHelpers.findAndHookMethod(View.class, "dispatchTouchEvent",
                MotionEvent.class, touchHook);

        // 按键事件 Hook — 用于监听音量键切换编辑模式/导航选择控件
        ActivityKeyHook keyHook = new ActivityKeyHook(sEditorOrchestrator);
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                KeyEvent.class, keyHook);
    }

    /** 注册 IPC 观察者 — 监听来自系统服务的规则变更通知并推送给编辑器 */
    private void registerObserver(String packageName) {
        RuleServiceClient gmManager = RuleServiceClient.getDefault();
        Logger.d(TAG, "[GodMode] registering observer for: " + packageName);
        // addObserver 通过 IPC 注册回调，当规则变更时推送 EditModeChanged + onViewRuleChanged
        // 通过 switchProp / actRuleProp 分发状态；BLOCKED 状态的应用阻止编辑模式启动
        gmManager.addObserver(packageName, new ServiceObserver());
    }

    // =========================================================================
    // 状态管理 — 通知编辑模式变更和规则变更给 ServiceObserver 回调处理
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
            switchProp.set(enable);                        // 通知开关属性变更，触发编辑模式切换
        }
        sEditorOrchestrator.setDisplay(enable);
    }

    public static void notifyViewRulesChanged(ActRules actRules) {
        if (actRules == null) return;
        sEventBus.post(new RulesChangedEvent(
                loadPackageParam != null ? loadPackageParam.packageName : "", actRules));
    }

    // 模块资源注入 — ModuleResources 在 injectIntoTargetApp 时被调用，通过 ModuleResources.injectInto() 注入模块资源
}
