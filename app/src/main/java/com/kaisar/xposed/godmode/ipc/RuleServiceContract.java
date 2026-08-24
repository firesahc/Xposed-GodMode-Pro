package com.kaisar.xposed.godmode.ipc;

/** Constants shared by the 6.10 Binder client and system_server service. */
public final class RuleServiceContract {
    public static final String SERVICE_NAME = "godmode";
    public static final String DESCRIPTOR =
            "com.kaisar.xposed.godmode.ipc.contract.IRuleService";
    public static final int PROTOCOL_VERSION = 61000;
    public static final int BUILD_VERSION_CODE = 61000;
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

    private RuleServiceContract() {}
}
