package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;

public final class RuleMutationResult implements Parcelable {
    public final int status;
    public final String requestId;
    public final String packageName;
    public final long generation;
    public final String value;
    public final String message;

    public RuleMutationResult(int status, String requestId, String packageName,
                              long generation, String value, String message) {
        this.status = status;
        this.requestId = requestId;
        this.packageName = packageName;
        this.generation = generation;
        this.value = value;
        this.message = message;
    }

    private RuleMutationResult(Parcel in) {
        status = in.readInt();
        requestId = in.readString();
        packageName = in.readString();
        generation = in.readLong();
        value = in.readString();
        message = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(status);
        dest.writeString(requestId);
        dest.writeString(packageName);
        dest.writeLong(generation);
        dest.writeString(value);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<RuleMutationResult> CREATOR = new Creator<RuleMutationResult>() {
        @Override public RuleMutationResult createFromParcel(Parcel in) {
            return new RuleMutationResult(in);
        }
        @Override public RuleMutationResult[] newArray(int size) {
            return new RuleMutationResult[size];
        }
    };
}
