package com.kaisar.xposed.godmode.ipc;

import com.kaisar.xposed.godmode.BuildConfig;

/** Constants shared by the 6.10 Binder client and system_server service. */
public final class RuleServiceContract {
    public static final String SERVICE_NAME = "godmode";
    public static final String DESCRIPTOR =
            "com.kaisar.xposed.godmode.ipc.contract.IRuleService";
    public static final int PROTOCOL_VERSION = 61000;
    // 构建身份随构建自动取值：客户端与服务端同源同值；仅当 system_server
    // 驻留旧版 APK 的服务时才会不等，从而正确触发"更新并重启"提示。
    public static final int BUILD_VERSION_CODE = BuildConfig.VERSION_CODE;
    public static final String CONTRACT_FINGERPRINT =
            "iruleservice-61000-fd-mutate-v3";
    public static final String GLOBAL_SCOPE = "*";

    public static final int STARTING = 0;
    public static final int READY = 1;
    public static final int REBOOT_REQUIRED = 2;
    public static final int FAILED = 3;

    public static final int SNAPSHOT_READY = 0;
    public static final int SNAPSHOT_EMPTY = 1;
    public static final int SNAPSHOT_UNAVAILABLE = 2;

    public static final int OP_EDIT = 1;
    public static final int OP_RESTORE = 2;
    public static final int OP_MUTATION = 3;
    public static final int OP_BACKUP = 4;

    public static final int MUTATION_WRITE = 1;
    public static final int MUTATION_UPDATE = 2;
    public static final int MUTATION_DELETE = 3;
    public static final int MUTATION_DELETE_ALL = 4;
    public static final int MUTATION_SET_TOOLBAR = 5;

    public static final int RESULT_COMMITTED = 0;
    public static final int RESULT_NO_CHANGE = 1;
    public static final int RESULT_BUSY = 2;
    public static final int RESULT_REJECTED = 3;
    public static final int RESULT_WRITE_FAILED = 4;
    public static final int RESULT_REBOOT_REQUIRED = 5;
    public static final int RESULT_INVALID = 6;
    public static final int RESULT_UNCERTAIN = 7;
    public static final int RESULT_STALE = 8;
    public static final int RESULT_EXPIRED = 9;
    public static final int RESULT_OWNER_MISMATCH = 10;
    public static final int RESULT_ALREADY_UNDONE = 11;

    /**
     * 终态成功 — 提交已生效或本就无变化，调用方可清除诊断并视为完成。
     * <p>
     * 唯一真值来源：替代各处手写的 {@code == COMMITTED || == NO_CHANGE}，
     * 防止未来新增成功语义时漏改某处（见 6.10 保留/刷新投影契约）。
     */
    public static boolean isTerminalSuccess(int status) {
        return status == RESULT_COMMITTED || status == RESULT_NO_CHANGE;
    }

    /**
     * 可重试瞬态 — 忙/写失败/不确定，调用方应保留当前投影供对账或重试，
     * 不得刷新权威状态（见 6.10 BUSY / WRITE_FAILED / UNCERTAIN 保留条款）。
     * <p>
     * 与 {@code EditorUndoController} 的瞬态判定同组；收敛于此一处，
     * 后续语义变化只改这里。
     */
    public static boolean isRetryableTransient(int status) {
        return status == RESULT_BUSY
                || status == RESULT_WRITE_FAILED
                || status == RESULT_UNCERTAIN;
    }

    /** 是否为不确定终态（需走快照/历史对账，不得重放写入）。 */
    public static boolean isUncertain(int status) {
        return status == RESULT_UNCERTAIN;
    }

    private RuleServiceContract() {}
}
