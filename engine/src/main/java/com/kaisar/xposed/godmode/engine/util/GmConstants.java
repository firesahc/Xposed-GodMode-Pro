package com.kaisar.xposed.godmode.engine.util;

/**
 * GodMode 引擎全局常量。
 * 集中管理所有模块共享的字符串、标签和配置常量。
 */
public final class GmConstants {

    private GmConstants() {
        // 工具类不可实例化
    }

    /** View Tag 标识 — GM 组件标记，用于遍历时跳过自身覆盖层视图 */
    public static final String TAG_GM_CMP = "gm_cmp";

    /** 可重复匹配视图的最大结果数限制，防止 RecyclerView 等大量匹配时 OOM */
    public static final int MAX_REPEATABLE_RESULTS = 50;

    /** GodMode 数据根目录（运行于 system_server 进程，需 SELinux 可访问） */
    public static final String DATA_DIR = "/data/misc/godmode";

    // 覆盖层颜色常量 — 使用 ARGB 字面值避免 engine 模块依赖 android.graphics.Color
    public static final int OVERLAY_COLOR_RED = 0x96FF0000;      // Color.argb(150, 255, 0, 0)
    public static final int OVERLAY_COLOR_GREEN = 0x968BC34B;    // Color.argb(150, 139, 195, 75)
    public static final int OVERLAY_COLOR_ORANGE = 0x96FFA500;   // Color.argb(150, 255, 165, 0)

    // 动画时长常量
    public static final int PARTICLE_ANIM_DURATION_MS = 1000;

    // 文件大小限制常量
    public static final int MAX_IMAGE_FILE_SIZE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_LOG_FILE_SIZE_BYTES = 2 * 1024 * 1024;
}
