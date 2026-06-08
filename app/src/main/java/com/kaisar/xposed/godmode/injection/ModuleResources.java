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
 * 濡€虫健鐠у嫭绨▔銊ュ弳閸?閳?鐏?GodMode 濡€虫健鐠у嫭绨▔銊ュ弳閻╊喗鐖ｆ惔鏃傛暏閻?AssetManager閵?
 * 娴?GodModeInjector 閹绘劕褰囬惃鍕缁斿浜寸拹锝冣偓?
 */
public final class ModuleResources {

    private static final String TAG = "GodMode";
    private static String sModulePath;
    private static boolean sInitialized;

    private ModuleResources() {}

    /** 閸?initZygote 闂冭埖顔岄崚婵嗩潗閸栨牗膩閸ф绁┃鎰熅瀵?*/
    public static void init(String modulePath, Resources moduleRes) {
        sModulePath = modulePath;
        sInitialized = true;
        GmResources.init(moduleRes);
    }

    public static boolean isInitialized() {
        return sInitialized;
    }

    /**
     * 鐏?GodMode 濡€虫健鐠у嫭绨▔銊ュ弳閻╊喗鐖ｆ惔鏃傛暏閻?Resources閵?
     * 娴ｅ灝绶遍崷銊ф窗閺嶅洤绨查悽銊よ厬濞撳弶鐓嬬憰鍡欐磰鐏?UI 閺冭泛褰叉禒銉ゅ▏閻劍膩閸ф娈戠敮鍐ㄧ湰閵嗕礁鐡х粭锔胯閸滃苯娴橀悧鍥カ濠ф劑鈧?
     */
    public static void injectInto(Resources res) {
        if (res == null) return;
        try {
            res.getString(R.string.res_inject_success);
            return; // 瀹稿弶鏁為崗?
        } catch (Resources.NotFoundException e) {
            // 鐏忔碍婀▔銊ュ弳 閳?缂佈呯敾閹笛嗩攽濞夈劌鍙嗗ù浣衡柤
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
