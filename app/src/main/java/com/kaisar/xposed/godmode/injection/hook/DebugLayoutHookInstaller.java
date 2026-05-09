package com.kaisar.xposed.godmode.injection.hook;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.injection.ViewHelper;
import com.kaisar.xposed.godmode.injection.util.Logger;
import com.kaisar.xposed.godmode.injection.util.Property;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 安装 Hook 以启用 Android 隐藏的"调试布局"功能。
 * <p>
 * 当 GodMode 编辑模式激活时，显示每个 View 的边距/内边距/焦点覆盖层，
 * 帮助用户在选中过程中看清视图边界。同时禁止 GM 覆盖层视图自身的调试绘制。
 */
public final class DebugLayoutHookInstaller {

    private DebugLayoutHookInstaller() {}

    public static void install(Property<Boolean> switchProp) {
        try {
            if (Build.VERSION.SDK_INT < 29) {
                installLegacyHooks(switchProp);
            } else {
                installModernHooks(switchProp);
            }
            // 抑制 GM 覆盖层视图（Tag = TAG_GM_CMP）的调试绘制
            suppressGmOverlayDebugDraw();
        } catch (Throwable e) {
            Logger.e(TAG, "Hook debug layout error", e);
        }
    }

    /** API < 29：Hook SystemProperties.native_get_boolean */
    private static void installLegacyHooks(Property<Boolean> switchProp) {
        SystemPropertiesHook hook = new SystemPropertiesHook();
        switchProp.addOnPropertyChangeListener(hook);
        XposedHelpers.findAndHookMethod("android.os.SystemProperties",
                ClassLoader.getSystemClassLoader(),
                "native_get_boolean", String.class, boolean.class, hook);
    }

    /** API >= 29：Hook SystemProperties.native_get + DisplayProperties.debug_layout */
    private static void installModernHooks(Property<Boolean> switchProp) {
        SystemPropertiesStringHook stringHook = new SystemPropertiesStringHook();
        switchProp.addOnPropertyChangeListener(stringHook);
        XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.os.SystemProperties", ClassLoader.getSystemClassLoader()),
                "native_get", stringHook);

        DisplayPropertiesHook displayHook = new DisplayPropertiesHook();
        switchProp.addOnPropertyChangeListener(displayHook);
        XposedHelpers.findAndHookMethod("android.sysprop.DisplayProperties",
                ClassLoader.getSystemClassLoader(),
                "debug_layout", displayHook);
    }

    /** 禁用 GM 覆盖层视图的调试绘制 */
    private static void suppressGmOverlayDebugDraw() {
        XposedHelpers.findAndHookMethod(ViewGroup.class, "onDebugDrawMargins",
                Canvas.class, Paint.class, XC_MethodReplacement.DO_NOTHING);

        XC_MethodHook disableDebugDraw = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                if (ViewHelper.TAG_GM_CMP.equals(view.getTag())) {
                    param.setResult(null);
                }
            }
        };
        XposedHelpers.findAndHookMethod(ViewGroup.class, "onDebugDraw", Canvas.class, disableDebugDraw);
        XposedHelpers.findAndHookMethod(View.class, "debugDrawFocus", Canvas.class, disableDebugDraw);
    }
}
