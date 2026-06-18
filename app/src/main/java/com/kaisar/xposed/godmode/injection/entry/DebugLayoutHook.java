package com.kaisar.xposed.godmode.injection.entry;

import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.engine.Property;
import com.kaisar.xposed.godmode.engine.util.Logger;

import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 调试布局模式 Hook — 当 Android 开发者选项中的「显示布局边界」开启时，
 * 通过 Hook SystemProperties 和 DisplayProperties 使 GodMode 编辑器面板显示控件边界。
 * <p>
 * 兼容 Android 10+（DisplayProperties API）和旧版本（SystemProperties API），
 * 同时屏蔽 GodMode 自身覆盖层的 debug draw 避免干扰显示。
 */
public final class DebugLayoutHook {

    private static final String TAG = "DebugLayoutHook";

    private DebugLayoutHook() {}

    public static void install(Property<Boolean> switchProp) {
        boolean modernOk = false, suppressOk = false;
        try {
            modernOk = installModernHooksSafe(switchProp);
        } catch (Throwable e) {
            Logger.e(TAG, "[DebugLayout] Hook debug layout properties error (non-fatal)", e);
        }
        try {
            suppressOk = suppressGmOverlayDebugDrawSafe();
        } catch (Throwable e) {
            Logger.e(TAG, "[DebugLayout] Hook debug draw suppression error (non-fatal)", e);
        }
        Logger.i(TAG, String.format("[DebugLayout] install result: modern=%b suppress=%b",
                modernOk, suppressOk));
    }

    private static boolean installModernHooksSafe(Property<Boolean> switchProp) {
        boolean ok = false;
        try {
            ModernHook stringHook = new ModernHook();
            switchProp.addOnPropertyChangeListener(stringHook);
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass("android.os.SystemProperties", ClassLoader.getSystemClassLoader()),
                    "native_get", stringHook);
            ok = true;
        } catch (Throwable t) {
            Logger.w(TAG, "[DebugLayout] native_get hook failed: " + t.getMessage());
        }
        try {
            DisplayHook displayHook = new DisplayHook();
            switchProp.addOnPropertyChangeListener(displayHook);
            XposedHelpers.findAndHookMethod("android.sysprop.DisplayProperties",
                    ClassLoader.getSystemClassLoader(),
                    "debug_layout", displayHook);
            ok = true;
        } catch (Throwable t) {
            Logger.w(TAG, "[DebugLayout] DisplayProperties hook failed: " + t.getMessage());
        }
        return ok;
    }

    private static boolean suppressGmOverlayDebugDrawSafe() {
        boolean ok = false;
        try {
            XposedHelpers.findAndHookMethod(ViewGroup.class, "onDebugDrawMargins",
                    Canvas.class, Paint.class, XC_MethodReplacement.DO_NOTHING);
            ok = true;
        } catch (Throwable t) {
            Logger.w(TAG, "[DebugLayout] onDebugDrawMargins hook failed: " + t.getMessage());
        }
        try {
            XC_MethodHook disableDebugDraw = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    if (TAG_GM_CMP.equals(view.getTag())) {
                        param.setResult(null);
                    }
                }
            };
            XposedHelpers.findAndHookMethod(ViewGroup.class, "onDebugDraw", Canvas.class, disableDebugDraw);
            XposedHelpers.findAndHookMethod(View.class, "debugDrawFocus", Canvas.class, disableDebugDraw);
            ok = true;
        } catch (Throwable t) {
            Logger.w(TAG, "[DebugLayout] debug draw suppression failed: " + t.getMessage());
        }
        return ok;
    }

    // =========================================================================
    // 调试布局 Hook 基类
    // =========================================================================

    private static abstract class BaseDebugHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

        protected volatile boolean mDebugLayout;

        @Override
        public void onPropertyChange(Boolean debugLayout) {
            mDebugLayout = debugLayout;
        }
    }

    private static final class ModernHook extends BaseDebugHook {

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (mDebugLayout && "debug.layout".equals(param.args[0])) {
                    param.setResult("true");
                }
            } catch (Throwable t) {
                Logger.w(TAG, "[DebugLayout] ModernHook error (suppressed): " + t.getMessage());
            }
        }
    }

    private static final class DisplayHook extends BaseDebugHook {

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (mDebugLayout) {
                    param.setResult(Optional.of(true));
                }
            } catch (Throwable t) {
                Logger.w(TAG, "[DebugLayout] DisplayHook error (suppressed): " + t.getMessage());
            }
        }
    }
}
