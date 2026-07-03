package com.kaisar.xposed.godmode.data;

import com.kaisar.xposed.godmode.engine.util.GmConstants;

/**
 * 数据面路径常量 — 集中管理 data/ 层使用的所有文件路径和信号名称。
 * <p>
 * 所有路径定义引用 {@link GmConstants#DATA_DIR} 作为基目录，
 * 确保与现有持久化代码的路径一致。
 * <p>
 * 【关键约束】{@code control/} 层不得直接拼接快照或信号文件路径，必须通过此常量类或
 * {@link RuleSnapshotStore}/{@link SignalStore} 的公共 API 访问。
 */
public final class DataBusConstants {

    private DataBusConstants() {
        // 工具类不可实例化
    }

    // ===== 快照目录 =====

    /** 快照文件基目录：{@code /data/misc/godmode/snapshots} */
    public static final String SNAPSHOT_DIR = GmConstants.DATA_DIR + "/snapshots";

    /** 快照文件后缀 */
    public static final String SNAPSHOT_FILE_SUFFIX = ".json";

    /** 临时文件后缀（用于原子写入的 .tmp） */
    public static final String TMP_FILE_SUFFIX = ".json.tmp";

    /** Generation 文件后缀 */
    public static final String GEN_FILE_SUFFIX = ".gen";

    // ===== 信号目录 =====

    /** 信号文件基目录：{@code /data/misc/godmode/signals} */
    public static final String SIGNAL_DIR = GmConstants.DATA_DIR + "/signals";

    // ===== 信号名称常量 =====

    /** 规则变更信号前缀。完整信号名为 {@code RULE_CHANGED_PREFIX + packageName} */
    public static final String RULE_CHANGED_PREFIX = "rule_changed:";

    /** 编辑模式变更信号 */
    public static final String EDIT_MODE_CHANGED = "edit_mode_changed";

    // ===== 工具方法 =====

    /**
     * 构造指定包的快照文件路径。
     *
     * @param packageName 包名
     * @return 快照 JSON 文件绝对路径
     */
    public static String getSnapshotFilePath(String packageName) {
        return SNAPSHOT_DIR + "/" + packageName + SNAPSHOT_FILE_SUFFIX;
    }

    /**
     * 构造指定包的临时文件路径。
     *
     * @param packageName 包名
     * @return 临时文件绝对路径
     */
    public static String getTmpFilePath(String packageName) {
        return SNAPSHOT_DIR + "/" + packageName + TMP_FILE_SUFFIX;
    }

    /**
     * 构造指定包的 generation 文件路径。
     *
     * @param packageName 包名
     * @return generation 文件绝对路径
     */
    public static String getGenFilePath(String packageName) {
        return SNAPSHOT_DIR + "/" + packageName + GEN_FILE_SUFFIX;
    }

    /**
     * 构造规则变更信号文件路径。
     *
     * @param packageName 包名
     * @return 信号文件绝对路径
     */
    public static String getRuleChangedSignalPath(String packageName) {
        return SIGNAL_DIR + "/" + RULE_CHANGED_PREFIX + packageName;
    }
}
