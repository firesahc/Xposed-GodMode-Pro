package com.kaisar.xposed.godmode.injection.entry;

import static com.kaisar.xposed.godmode.GodModeApplication.TAG;
import static com.kaisar.xposed.godmode.engine.util.GmConstants.TAG_GM_CMP;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
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
 * 閻庣懓顦抽ˉ?Hook 濞寸姰鍎遍幆搴ㄦ偨?Android 闂傚懏鍔樺Λ宀勬儍?閻犲鍟抽惁顖滄暜閸愩劎婀?闁告梻鍠曢崗姗€濡? * <p>
 * 鐟?GodMode 缂傚倹鐗炵欢顐⑽熼垾宕囩婵犵鍋撴繛鑼帛濡炲倿鏁嶇仦鐐枖缂佲偓閻戞妲ㄥ☉?View 闁汇劌瀚粩鐔烘崉?闁告劕鎳撶粩鐔烘崉?闁绘帪濡囬崑锝囨啺閸℃瑦纾伴悘鐐插亰缁? * 閻㈩垼鍠栨慨顏堟偨閵婏箑鐓曢柛锔哄姂閳ь剙顦懙鎴炴交閸モ斁鏌ゅ☉鎿冨幘濠€鍛€掗崨閭︽綊闁搞儲宕樼粩鐔兼偩鐏炵儵鍋撻崒姘€遍柡鍐ㄥ椤╋箑顫?GM 閻熸洖妫涘ú濠勪沪閸屾繍娼掗柛銉﹀礃閸ゆ粓鐓銈嗙暠閻犲鍟抽惁顖滅磼濡搫鐓戦柕? */
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
    // 闁告劕鎳橀崕?Hook 缂?    // =========================================================================

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
