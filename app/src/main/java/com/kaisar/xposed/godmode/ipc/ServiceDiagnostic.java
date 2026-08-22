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
                return new ServiceDiagnostic(type, DiagnosticMessages.BRIDGE_UNAVAILABLE_SUMMARY,
                        DiagnosticMessages.BRIDGE_UNAVAILABLE_ACTION, technicalDetail);
            case SERVICE_STARTING:
                return new ServiceDiagnostic(type, DiagnosticMessages.SERVICE_STARTING_SUMMARY,
                        DiagnosticMessages.SERVICE_STARTING_ACTION, technicalDetail);
            case BINDER_DIED:
                return new ServiceDiagnostic(type, DiagnosticMessages.BINDER_DIED_SUMMARY,
                        DiagnosticMessages.BINDER_DIED_ACTION, technicalDetail);
            case DESCRIPTOR_MISMATCH:
                return new ServiceDiagnostic(type, DiagnosticMessages.DESCRIPTOR_MISMATCH_SUMMARY,
                        DiagnosticMessages.DESCRIPTOR_MISMATCH_ACTION, technicalDetail);
            case CONTRACT_MISMATCH:
                return new ServiceDiagnostic(type, DiagnosticMessages.CONTRACT_MISMATCH_SUMMARY,
                        DiagnosticMessages.CONTRACT_MISMATCH_ACTION, technicalDetail);
            case PERMISSION_REJECTED:
                return new ServiceDiagnostic(type, DiagnosticMessages.PERMISSION_REJECTED_SUMMARY,
                        DiagnosticMessages.PERMISSION_REJECTED_ACTION, technicalDetail);
            case OPERATION_BUSY:
                return new ServiceDiagnostic(type, DiagnosticMessages.OPERATION_BUSY_SUMMARY,
                        DiagnosticMessages.OPERATION_BUSY_ACTION, technicalDetail);
            case COMMIT_UNCERTAIN:
                return new ServiceDiagnostic(type, DiagnosticMessages.COMMIT_UNCERTAIN_SUMMARY,
                        DiagnosticMessages.COMMIT_UNCERTAIN_ACTION, technicalDetail);
            case UNKNOWN:
            default:
                return new ServiceDiagnostic(Type.UNKNOWN, DiagnosticMessages.UNKNOWN_ERROR_SUMMARY,
                        DiagnosticMessages.UNKNOWN_ERROR_ACTION, technicalDetail);
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
