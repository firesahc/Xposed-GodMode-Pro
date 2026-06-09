package com.kaisar.xposed.godmode.injection;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.content.res.Resources;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.injection.util.GmResources;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 模块资源管理 — 提供 GodMode 模块资源注入目标应用 AssetManager 的功能。
 * 由 GodModeInjector 在初始化时调用。
 */
public final class ModuleResources {

    private static final String TAG = "GodMode";
    private static String sModulePath;
    private static boolean sInitialized;

    private ModuleResources() {}

    /** 在 initZygote 阶段初始化模块资源路径和资源实例 */
    public static void init(String modulePath, Resources moduleRes) {
        sModulePath = modulePath;
        sInitialized = true;
        GmResources.init(moduleRes);
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    /**
     * 将 GodMode 模块资源注入目标应用的 Resources 中。
     * 通过反射调用 AssetManager.addAssetPath 添加模块路径，
     * 使目标应用能加载模块的 UI 资源和字符串等资源。
     */
    public static void injectInto(Resources res) {
        if (res == null) return;
        try {
            res.getString(R.string.res_inject_success);
            return; // 已注入，跳过
        } catch (Resources.NotFoundException e) {
            // 未注入，需要执行注入流程
        }
        try {
            String path = sModulePath;
            if (path == null) {
                Logger.e(TAG, "[ModuleResources] module path not initialized");
                return;
            }
            AssetManager assets = res.getAssets();
            @SuppressLint("DiscouragedPrivateApi")
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            int cookie = (int) addAssetPath.invoke(assets, path);
            try {
                Logger.i(TAG, "[ModuleResources] " + res.getString(R.string.res_inject_success));
            } catch (Resources.NotFoundException e) {
                File f = new File(path);
                Logger.e(TAG, "[ModuleResources] injection failure! cookie=" + cookie
                        + " path=" + path + " exists=" + f.exists());
            }
        } catch (Exception e) {
            Logger.e(TAG, "[ModuleResources] inject failed", e);
        }
    }
}
