package com.kaisar.xposed.godmode.ipc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Stable, user-facing projection of a rule service failure. */
public final class ServiceDiagnostic {

    public enum Type {
        BRIDGE_UNAVAILABLE,
        SERVICE_STARTING,
        BINDER_DIED,
        DESCRIPTOR_MISMATCH,
        CONTRACT_MISMATCH,
        PERMISSION_REJECTED,
        OPERATION_BUSY,
        COMMIT_UNCERTAIN,
        UNKNOWN
    }

    private final Type mType;
    private final String mSummary;
    private final String mAction;
    private final String mTechnicalDetail;

    private ServiceDiagnostic(Type type, String summary, String action,
                              String technicalDetail) {
        mType = type;
        mSummary = summary;
        mAction = action;
        mTechnicalDetail = technicalDetail;
    }

    public static ServiceDiagnostic of(@NonNull Type type, @Nullable String technicalDetail) {
        switch (type) {
            case BRIDGE_UNAVAILABLE:
                return new ServiceDiagnostic(type, "规则服务桥接不可用",
                        "请检查 LSPosed 的 Android 系统框架作用域，然后重启系统",
                        technicalDetail);
            case SERVICE_STARTING:
                return new ServiceDiagnostic(type, "规则服务尚未就绪",
                        "请稍后重试；若长时间未恢复，请检查持久化日志",
                        technicalDetail);
            case BINDER_DIED:
                return new ServiceDiagnostic(type, "规则服务连接已断开",
                        "请退出当前编辑并重新进入", technicalDetail);
            case DESCRIPTOR_MISMATCH:
                return new ServiceDiagnostic(type, "规则服务接口版本不匹配",
                        "请确认模块已更新，然后重启系统", technicalDetail);
            case CONTRACT_MISMATCH:
                return new ServiceDiagnostic(type, "规则服务合同不匹配",
                        "请确认模块已完整更新，然后重启系统", technicalDetail);
            case PERMISSION_REJECTED:
                return new ServiceDiagnostic(type, "当前操作未获规则服务授权",
                        "请确认目标应用和调用范围正确", technicalDetail);
            case OPERATION_BUSY:
                return new ServiceDiagnostic(type, "规则服务正在处理其他操作",
                        "请等待当前编辑、备份或恢复完成后重试", technicalDetail);
            case COMMIT_UNCERTAIN:
                return new ServiceDiagnostic(type, "规则提交状态未知",
                        "请刷新规则后再操作，不要重复提交", technicalDetail);
            case UNKNOWN:
            default:
                return new ServiceDiagnostic(Type.UNKNOWN, "规则服务发生未知错误",
                        "请查看持久化日志中的详细原因", technicalDetail);
        }
    }

    static ServiceDiagnostic forServiceState(int state, String technicalDetail) {
        if (state == RuleServiceContract.STARTING) {
            return of(Type.SERVICE_STARTING, technicalDetail);
        }
        if (state == RuleServiceContract.REBOOT_REQUIRED) {
            return of(Type.CONTRACT_MISMATCH, technicalDetail);
        }
        return of(Type.UNKNOWN, technicalDetail);
    }

    static ServiceDiagnostic forResultStatus(int status, String technicalDetail) {
        if (status == RuleServiceContract.RESULT_BUSY) {
            return of(Type.OPERATION_BUSY, technicalDetail);
        }
        if (status == RuleServiceContract.RESULT_REJECTED) {
            return of(Type.PERMISSION_REJECTED, technicalDetail);
        }
        if (status == RuleServiceContract.RESULT_REBOOT_REQUIRED) {
            return of(Type.CONTRACT_MISMATCH, technicalDetail);
        }
        if (status == RuleServiceContract.RESULT_UNCERTAIN) {
            return of(Type.COMMIT_UNCERTAIN, technicalDetail);
        }
        return of(Type.UNKNOWN, technicalDetail);
    }

    @NonNull
    public Type getType() {
        return mType;
    }

    @NonNull
    public String getSummary() {
        return mSummary;
    }

    @NonNull
    public String getAction() {
        return mAction;
    }

    @Nullable
    public String getTechnicalDetail() {
        return mTechnicalDetail;
    }

    @NonNull
    public String getUserMessage() {
        return mSummary + "。" + mAction;
    }
}
