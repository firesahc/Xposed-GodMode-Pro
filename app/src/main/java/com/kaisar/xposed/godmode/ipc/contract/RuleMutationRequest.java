package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

/** Mutation envelope; ruleJson is the unchanged RuleRecord flat JSON representation. */
public final class RuleMutationRequest implements Parcelable {
    public final int operation;
    public final String requestId;
    public final String leaseToken;
    public final String packageName;
    public final String ruleJson;
    public final ParcelFileDescriptor mainImageFd;
    public final ParcelFileDescriptor modifiedImageFd;
    public final String value;
    public final boolean captureUndo;

    public RuleMutationRequest(int operation, String requestId, String leaseToken,
                               String packageName, String ruleJson,
                               ParcelFileDescriptor mainImageFd,
                               ParcelFileDescriptor modifiedImageFd,
                               String value) {
        this(operation, requestId, leaseToken, packageName, ruleJson, mainImageFd,
                modifiedImageFd, value, false);
    }

    public RuleMutationRequest(int operation, String requestId, String leaseToken,
                               String packageName, String ruleJson,
                               ParcelFileDescriptor mainImageFd,
                               ParcelFileDescriptor modifiedImageFd,
                               String value, boolean captureUndo) {
        this.operation = operation;
        this.requestId = requestId;
        this.leaseToken = leaseToken;
        this.packageName = packageName;
        this.ruleJson = ruleJson;
        this.mainImageFd = mainImageFd;
        this.modifiedImageFd = modifiedImageFd;
        this.value = value;
        this.captureUndo = captureUndo;
    }

    private RuleMutationRequest(Parcel in) {
        operation = in.readInt();
        requestId = in.readString();
        leaseToken = in.readString();
        packageName = in.readString();
        ruleJson = in.readString();
        mainImageFd = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
        modifiedImageFd = in.readParcelable(ParcelFileDescriptor.class.getClassLoader());
        value = in.readString();
        captureUndo = in.readInt() != 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(operation);
        dest.writeString(requestId);
        dest.writeString(leaseToken);
        dest.writeString(packageName);
        dest.writeString(ruleJson);
        dest.writeParcelable(mainImageFd, flags);
        dest.writeParcelable(modifiedImageFd, flags);
        dest.writeString(value);
        dest.writeInt(captureUndo ? 1 : 0);
    }

    @Override public int describeContents() {
        int contents = 0;
        if (mainImageFd != null) contents |= CONTENTS_FILE_DESCRIPTOR;
        if (modifiedImageFd != null) contents |= CONTENTS_FILE_DESCRIPTOR;
        return contents;
    }

    public static final Creator<RuleMutationRequest> CREATOR = new Creator<RuleMutationRequest>() {
        @Override public RuleMutationRequest createFromParcel(Parcel in) {
            return new RuleMutationRequest(in);
        }
        @Override public RuleMutationRequest[] newArray(int size) {
            return new RuleMutationRequest[size];
        }
    };
}
