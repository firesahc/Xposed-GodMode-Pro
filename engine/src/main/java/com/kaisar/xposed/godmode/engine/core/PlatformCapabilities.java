package com.kaisar.xposed.godmode.engine.core;

import android.os.Build;

/**
 * 集中式平台能力探测 — 所有版本判断集中于此，禁止散落各处。
 * <p>
 * 替代运行时、注入 Hook 和兼容工具中散落的
 * {@code Build.VERSION.SDK_INT} 判断。
 * 集中后可以一次修改覆盖所有使用点。
 * <p>
 * 所有方法均为 static，不可实例化。
 */
public final class PlatformCapabilities {

    private static final int SDK = Build.VERSION.SDK_INT;

    // ===== 匹配能力 =====

    /** RecyclerView Hook 需要 Android N+（API 24+），ClassLoader 可发现 androidx.recyclerview.widget */
    public static boolean supportsRecyclerViewHook() {
        return SDK >= 24;
    }

    // ===== UI/服务能力 =====

    /** Android 13+（API 33）通知权限需运行时请求 */
    public static boolean canRequestNotificationPermission() {
        return SDK >= Build.VERSION_CODES.TIRAMISU;
    }

    /** Android 14+（API 34）startForeground 必须声明前台服务类型 */
    public static boolean supportsForegroundServiceType() {
        return SDK >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    private PlatformCapabilities() {
        // 工具类不可实例化
    }
}
