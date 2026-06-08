package com.kaisar.xposed.godmode.injection.entry;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import com.kaisar.xposed.godmode.util.Logger;
import com.kaisar.xposed.godmode.engine.Property;

import java.util.Optional;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 鐎瑰顥?Hook 娴犮儱鎯庨悽?Android 闂呮劘妫岄惃?鐠嬪啳鐦敮鍐ㄧ湰"閸旂喕鍏橀妴? * <p>
 * 瑜?GodMode 缂傛牞绶Ο鈥崇础濠碘偓濞茬粯妞傞敍灞炬▔缁€鐑樼槨娑?View 閻ㄥ嫯绔熺捄?閸愬懓绔熺捄?閻掞妇鍋ｇ憰鍡欐磰鐏炲偊绱? * 鐢喖濮悽銊﹀煕閸︺劑鈧鑵戞潻鍥┾柤娑擃厾婀呭〒鍛邦潒閸ユ崘绔熼悾灞烩偓鍌氭倱閺冨墎顩﹀?GM 鐟曞棛娲婄仦鍌濐潒閸ユ崘鍤滈煬顐ゆ畱鐠嬪啳鐦紒妯哄煑閵? */
public final class DebugLayoutHook {

    private DebugLayoutHook() {}

    public static void install(Property<Boolean> switchProp) {
        boolean legacyOk = false, modernOk = false, suppressOk = false;
        try {
            if (Build.VERSION.SDK_INT < 29) {
                legacyOk = installLegacyHooksSafe(switchProp);
            } else {
                modernOk = installModernHooksSafe(switchProp);
            }
        } catch (Throwable e) {
            Logger.e(TAG, "[DebugLayout] Hook debug layout properties error (non-fatal)", e);
        }
        try {
            suppressOk = suppressGmOverlayDebugDrawSafe();
        } catch (Throwable e) {
            Logger.e(TAG, "[DebugLayout] Hook debug draw suppression error (non-fatal)", e);
        }
        Logger.i(TAG, String.format("[DebugLayout] install result: legacy=%b modern=%b suppress=%b",
                legacyOk, modernOk, suppressOk));
    }

    private static boolean installLegacyHooksSafe(Property<Boolean> switchProp) {
        try {
            LegacyHook hook = new LegacyHook();
            switchProp.addOnPropertyChangeListener(hook);
            XposedHelpers.findAndHookMethod("android.os.SystemProperties",
                    ClassLoader.getSystemClassLoader(),
                    "native_get_boolean", String.class, boolean.class, hook);
            return true;
        } catch (Throwable t) {
            Logger.w(TAG, "[DebugLayout] legacy hook failed (non-fatal): " + t.getMessage());
            return false;
        }
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
    // 閸愬懘鍎?Hook 缁?    // =========================================================================

    private static abstract class BaseDebugHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

        protected volatile boolean mDebugLayout;

        @Override
        public void onPropertyChange(Boolean debugLayout) {
            mDebugLayout = debugLayout;
        }
    }

    private static final class LegacyHook extends BaseDebugHook {

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (mDebugLayout && "debug.layout".equals(param.args[0])) {
                    param.setResult(true);
                }
            } catch (Throwable t) {
                Logger.w(TAG, "[DebugLayout] LegacyHook error (suppressed): " + t.getMessage());
            }
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
