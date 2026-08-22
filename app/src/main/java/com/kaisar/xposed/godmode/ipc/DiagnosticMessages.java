package com.kaisar.xposed.godmode.ipc;

/**
 * User-facing diagnostic strings for the IPC layer. Collected here instead of resource
 * entries because the system_server side cannot resolve this module's R.string.
 */
final class DiagnosticMessages {

    private DiagnosticMessages() { }

    // ---- SERVICE_DIAGNOSTIC: static summary/action pairs returned by ServiceDiagnostic.of(). ----

    static final String BRIDGE_UNAVAILABLE_SUMMARY = "规则服务桥接不可用";
    static final String BRIDGE_UNAVAILABLE_ACTION = "请检查 LSPosed 的 Android 系统框架作用域，然后重启系统";

    static final String SERVICE_STARTING_SUMMARY = "规则服务尚未就绪";
    static final String SERVICE_STARTING_ACTION = "请稍后重试；若长时间未恢复，请检查持久化日志";

    static final String BINDER_DIED_SUMMARY = "规则服务连接已断开";
    static final String BINDER_DIED_ACTION = "请退出当前编辑并重新进入";

    static final String DESCRIPTOR_MISMATCH_SUMMARY = "规则服务接口版本不匹配";
    static final String DESCRIPTOR_MISMATCH_ACTION = "请确认模块已更新，然后重启系统";

    static final String CONTRACT_MISMATCH_SUMMARY = "规则服务合同不匹配";
    static final String CONTRACT_MISMATCH_ACTION = "请确认模块已完整更新，然后重启系统";

    static final String PERMISSION_REJECTED_SUMMARY = "当前操作未获规则服务授权";
    static final String PERMISSION_REJECTED_ACTION = "请确认目标应用和调用范围正确";

    static final String OPERATION_BUSY_SUMMARY = "规则服务正在处理其他操作";
    static final String OPERATION_BUSY_ACTION = "请等待当前编辑、备份或恢复完成后重试";

    static final String COMMIT_UNCERTAIN_SUMMARY = "规则提交状态未知";
    static final String COMMIT_UNCERTAIN_ACTION = "请刷新规则后再操作，不要重复提交";

    static final String UNKNOWN_ERROR_SUMMARY = "规则服务发生未知错误";
    static final String UNKNOWN_ERROR_ACTION = "请查看持久化日志中的详细原因";

    // ---- CLIENT_DETAIL: technicalDetail arguments for ServiceDiagnostic.of(); %s/%d templates are filled via String.format at each call site. ----

    static final String DESCRIPTOR_MISMATCH_DETAIL = "规则服务 descriptor 不匹配: %s";
    static final String IDENTITY_FINGERPRINT_MISMATCH_DETAIL = "规则服务身份或合同指纹不匹配";
    static final String SERVICE_NOT_READY_STATE_DETAIL = "规则服务尚未就绪，state=%d";
    static final String HANDSHAKE_FAILED_DETAIL = "规则服务握手失败: %s";
    static final String HANDSHAKE_BINDER_DEAD_DETAIL = "规则服务握手期间 Binder 已死亡: %s";
    static final String HANDSHAKE_UNEXPECTED_FAILURE_DETAIL = "规则服务握手异常: %s";
    static final String BINDER_DIED_AWAITING_RECONNECT_DETAIL = "规则服务 Binder 已死亡，等待重新连接";
    static final String BRIDGE_NOT_INSTALLED_DETAIL = "XServiceManager 桥接未安装";
    static final String BRIDGE_NOT_IN_SYSTEM_SERVER_DETAIL = "XServiceManager 未运行在 system_server，注入失败";
    static final String BRIDGE_READY_SERVICE_UNREGISTERED_DETAIL = "桥接已就绪，规则服务尚未注册";
    static final String EDIT_CLOSE_PENDING_COMMIT_DETAIL = "编辑正在关闭，请等待当前提交完成";
    static final String OPERATION_LEASE_MISSING_DETAIL = "规则服务未返回操作租约";
    static final String OPERATION_CLOSE_RESULT_MISSING_DETAIL = "规则服务未返回关闭结果";
    static final String MUTATE_RESULT_MISSING_DETAIL = "规则服务未返回提交结果";
    static final String MUTATE_UNCERTAIN_REQUEST_ID_DETAIL = "规则提交结果未知，请刷新规则后再操作 (requestId=%s)";
    static final String IMAGE_PIPE_WRITE_FAILED_DETAIL = "图片管道写入失败: %s";
    static final String MUTATE_READBACK_NOT_FOUND_REQUEST_ID_DETAIL = "规则服务读回未发现本次提交，请刷新规则后再操作 (requestId=%s)";
    static final String MUTATE_RECONCILE_UNKNOWN_REQUEST_ID_DETAIL = "规则提交状态未知，请刷新规则后再操作 (requestId=%s)";
    static final String IMAGE_RECYCLED_MUTATION_CANCELLED_DETAIL = "图片已回收，取消规则提交";
    static final String IMAGE_PIPE_CREATE_FAILED_DETAIL = "创建图片管道失败: %s";

    // ---- EXCEPTIONS: exception messages are not user-facing diagnostics; kept here only to avoid stray literals. ----

    static final String SNAPSHOT_READ_FAILED_EXCEPTION = "无法读取规则快照";
}
