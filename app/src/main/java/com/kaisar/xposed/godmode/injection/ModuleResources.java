package com.kaisar.xposed.godmode.injection;

import android.annotation.SuppressLint;
import android.content.res.AssetManager;
import android.content.res.Resources;

import com.kaisar.xposed.godmode.R;
import com.kaisar.xposed.godmode.injection.util.GmResources;
import com.kaisar.xposed.godmode.util.Logger;

import java.io.File;
import java.lang.reflect.Method;

/**
 * 妯″潡璧勬簮娉ㄥ叆鍣?鈥?灏?GodMode 妯″潡璧勬簮娉ㄥ叆鐩爣搴旂敤鐨?AssetManager銆?
 * 浠?GodModeInjector 鎻愬彇鐨勭嫭绔嬭亴璐ｃ€?
 */
public final class ModuleResources {

    private static final String TAG = "GodMode";
    private static String sModulePath;
    private static boolean sInitialized;

    private ModuleResources() {}

    /** 鍦?initZygote 闃舵鍒濆鍖栨ā鍧楄祫婧愯矾寰?*/
    public static void init(String modulePath, Resources moduleRes) {
        sModulePath = modulePath;
        sInitialized = true;
        GmResources.init(moduleRes);
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    /**
     * 灏?GodMode 妯″潡璧勬簮娉ㄥ叆鐩爣搴旂敤鐨?Resources銆?
     * 浣垮緱鍦ㄧ洰鏍囧簲鐢ㄤ腑娓叉煋瑕嗙洊灞?UI 鏃跺彲浠ヤ娇鐢ㄦā鍧楃殑甯冨眬銆佸瓧绗︿覆鍜屽浘鐗囪祫婧愩€?
     */
    public static void injectInto(Resources res) {
        if (res == null) return;
        try {
            res.getString(R.string.res_inject_success);
            return; // 宸叉敞鍏?
        } catch (Resources.NotFoundException e) {
            // 灏氭湭娉ㄥ叆 鈥?缁х画鎵ц娉ㄥ叆娴佺▼
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
