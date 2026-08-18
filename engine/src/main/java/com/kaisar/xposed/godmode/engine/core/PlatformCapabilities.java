package com.kaisar.xposed.godmode.engine.core;

import android.os.Build;

import java.util.Locale;

/**
 * 集中式平台能力探测 — 所有版本/厂商判断集中于此，禁止散落各处。
 * <p>
 * 替代运行时、注入 Hook 和兼容工具中散落的
 * {@code Build.VERSION.SDK_INT} 判断和厂商检测逻辑。
 * 集中后可以一次修改覆盖所有使用点。
 * <p>
 * 所有方法均为 static，不可实例化。
 */
public final class PlatformCapabilities {

    private static final int SDK = Build.VERSION.SDK_INT;

    // ===== 匹配能力 =====

    /** RecyclerView Hook 需要 Android N+（API 24+）：ClassLoader 可访问 androidx.recyclerview.widget */
    public static boolean supportsRecyclerViewHook() {
        return SDK >= 24;
    }

    /** ViewOutlineProvider 需要 Android L+（API 21+） */
    public static boolean supportsViewOutlineProvider() {
        return SDK >= 21;
    }

    // ===== Workaround =====

    /** API 25- 需要旧版 LayoutListener 回退（某些国产 ROM 在 API 25- 上有布局回调兼容问题） */
    public static boolean requiresLegacyLayoutListener() {
        return SDK <= 25;
    }

    // ===== 厂商检测 =====

    private static final String MANUFACTURER =
            Build.MANUFACTURER != null
                    ? Build.MANUFACTURER.toLowerCase(Locale.ROOT) : "";

    public static boolean isXiaomi() {
        return MANUFACTURER.contains("xiaomi");
    }

    /** HyperOS 是小米基于 Android 14+ 的新系统 */
    public static boolean isHyperOS() {
        return isXiaomi() && hasHyperOSProperty();
    }

    public static boolean isHuawei() {
        return MANUFACTURER.contains("huawei");
    }

    public static boolean isSamsung() {
        return MANUFACTURER.contains("samsung");
    }

    public static boolean isOppo() {
        return MANUFACTURER.contains("oppo");
    }

    public static boolean isVivo() {
        return MANUFACTURER.contains("vivo");
    }

    // ===== 内部辅助 =====

    /**
     * 检测 HyperOS 属性。HyperOS 在 Build.DISPLAY 中包含 "HyperOS" 字符串。
     */
    private static boolean hasHyperOSProperty() {
        String display = Build.DISPLAY;
        return display != null && display.contains("HyperOS");
    }

    private PlatformCapabilities() {
        // 工具类不可实例化
    }
}
