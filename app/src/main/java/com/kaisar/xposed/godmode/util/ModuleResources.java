package com.kaisar.xposed.godmode.util;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.content.res.Resources;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.util.GmResources;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 模块资源管理 — 提供 GodMode 模块资源注入目标应用 AssetManager 的功能。
 * 由注入层在 Activity 创建阶段初始化。
 * <p>
 * 与 {@code GmResources}（资源访问器）互补而非重复：本类负责“写”（注入），
 * {@code GmResources} 负责“读”；{@link #init} 内委托 {@code GmResources.init} 只是共享
 * 初始化入口，不代表职责合并。DO NOT 合并两者。
 */
public final class ModuleResources {

    private static final String TAG = "ModuleResources";
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
     *
     * @param res 目标应用的 Resources 实例
     * @return true 表示注入成功，false 表示注入失败（需由调用方决定 fallback 策略）
     */
    public static boolean injectInto(Resources res) {
        if (res == null) return false;
        try {
            res.getString(R.string.res_inject_success);
            return true; // 已注入
        } catch (Resources.NotFoundException e) {
            // 未注入，需要执行注入流程
        }
        try {
            String path = sModulePath;
            if (path == null) {
                Logger.e(TAG, "module path not initialized");
                return false;
            }
            File f = new File(path);
            if (!f.exists()) {
                Logger.e(TAG, "module apk not found: " + path);
                return false;
            }
            AssetManager assets = res.getAssets();
            @SuppressLint("DiscouragedPrivateApi")
            Method addAssetPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            int cookie = (int) addAssetPath.invoke(assets, path);
            if (cookie == 0) {
                Logger.e(TAG, "addAssetPath returned 0 for path=" + path);
                return false;
            }
            // 验证注入是否生效
            try {
                Logger.i(TAG, res.getString(R.string.res_inject_success));
                return true;
            } catch (Resources.NotFoundException e) {
            Logger.e(TAG, "injection verification failed! cookie=" + cookie
                        + " path=" + path + " exists=" + f.exists());
                return false;
            }
        } catch (Exception e) {
            Logger.e(TAG, "inject failed", e);
            return false;
        }
    }
}
