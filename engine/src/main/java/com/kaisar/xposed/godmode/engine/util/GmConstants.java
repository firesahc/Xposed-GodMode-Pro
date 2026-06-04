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
}
