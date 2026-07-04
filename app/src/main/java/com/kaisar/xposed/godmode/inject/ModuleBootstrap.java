package com.kaisar.xposed.godmode.inject;

import android.app.Activity;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.event.EventBus;
import com.kaisar.xposed.godmode.engine.event.RulesChangedEvent;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.ModuleResources;
import com.kaisar.xposed.godmode.editor.RuleEditorClient;
import com.kaisar.xposed.godmode.injection.editor.EditorOrchestrator;
import com.kaisar.xposed.godmode.injection.util.BlockListChecker;
import com.kaisar.xposed.godmode.rule.ActRules;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * GodMode 的 Xposed 入口路由。
 * <p>
 * 实现 {@link IXposedHookZygoteInit} 用于在 Zygote 初始化阶段准备模块资源，
 * 实现 {@link IXposedHookLoadPackage} 用于拦截目标应用并分流：
 * <ul>
 *   <li>{@code "android"} 包名 → 委托给 {@link ServiceBootstrapper} 注入 system_server 服务</li>
 *   <li>普通应用 → 委托给 {@link AppInjector} 注入目标进程</li>
 * </ul>
 */
public final class ModuleBootstrap implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private static final String TAG = "ModuleBootstrap";

    // ===== 静态开关和编辑器 =====
    private final static Property<Boolean> switchProp = new Property<>(false);
    private static final EventBus sEventBus = EventBus.getDefault();
    private static final EditorOrchestrator sEditorOrchestrator = new EditorOrchestrator(switchProp, RuleEditorClient.getInstance());

    // ===== 运行时状态 =====
    private static volatile XC_LoadPackage.LoadPackageParam sLoadPackageParam;
    private static volatile State sState = State.UNKNOWN;

    private enum State { UNKNOWN, ALLOWED, BLOCKED }

    // ===== 静态 getter =====

    /** 获取编辑器开关属性 */
    public static Property<Boolean> getSwitchProp() { return switchProp; }

    /** 查询编辑器开关状态 */
    public static boolean isSwitchEnabled() { return switchProp.get(); }

    /** 获取编辑器编排器实例 */
    public static EditorOrchestrator getEditorOrchestrator() { return sEditorOrchestrator; }

    /** 获取当前加载包参数（可能为 null） */
    public static XC_LoadPackage.LoadPackageParam getLoadPackageParam() { return sLoadPackageParam; }

    /** 获取当前应用包名（可能为 null） */
    public static String getPackageName() {
        return sLoadPackageParam != null ? sLoadPackageParam.packageName : null;
    }

    /** 获取 EventBus 实例 */
    public static EventBus getEventBus() { return sEventBus; }

    // =========================================================================
    // Zygote 初始化 — initZygote 阶段准备模块资源
    // =========================================================================

    @Override
    public void initZygote(StartupParam startupParam) {
        Resources moduleRes = null;
        try {
            AssetManager am = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.invoke(am, startupParam.modulePath);
            moduleRes = new Resources(am, null, null);
        } catch (Exception e) {
            Logger.e(TAG, "[Bootstrap] Failed to create module Resources via reflection", e);
        }
        ModuleResources.init(startupParam.modulePath, moduleRes);
    }

    // =========================================================================
    // 包加载路由 — 分流到系统服务或目标应用
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

        final String packageName = lpp.packageName;
        Logger.i(TAG, "[Bootstrap] routing package: " + packageName
                + " process: " + lpp.processName);

        if ("android".equals(packageName)) {
            sLoadPackageParam = lpp;
            Logger.i(TAG, "[Bootstrap] injecting RuleServiceServer into system_server");
            ServiceBootstrapper.bootstrap();
            return;
        }

        if (!shouldInject(packageName, lpp)) {
            return;
        }

        sLoadPackageParam = lpp;
        new AppInjector().inject(lpp, packageName);
    }

    private static boolean shouldInject(String packageName, XC_LoadPackage.LoadPackageParam lpp) {
        String processName = lpp.processName;
        if (!TextUtils.equals(packageName, processName)) {
            Logger.d(TAG, "[Bootstrap] skip non-main process: pkg=" + packageName
                    + " process=" + processName);
            return false;
        }
        if (BlockListChecker.isBlocked(packageName)) {
            Logger.d(TAG, "[Bootstrap] skip blocked package before hooks: " + packageName);
            return false;
        }
        return true;
    }

    // =========================================================================
    // 状态管理 — 通知编辑模式变更和规则变更
    // =========================================================================

    public static void notifyEditModeChanged(boolean enable) {
        if (sLoadPackageParam == null) {
            Logger.w(TAG, "[Bootstrap] edit mode change ignored: loadPackageParam not ready");
            return;
        }
        if (sState == State.UNKNOWN) {
            sState = BlockListChecker.isBlocked(getPackageName())
                    ? State.BLOCKED : State.ALLOWED;
        }
        Logger.i(TAG, "[Bootstrap] edit mode " + enable + " state=" + sState
                + " pkg=" + getPackageName());
        if (sState == State.ALLOWED) {
            switchProp.set(enable);
        }
        sEditorOrchestrator.setDisplay(enable);
    }

    public static void notifyViewRulesChanged(ActRules actRules) {
        if (actRules == null) return;
        sEventBus.post(new RulesChangedEvent(
                getPackageName() != null ? getPackageName() : "", actRules));
    }
}
