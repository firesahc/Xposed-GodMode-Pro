package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;

public final class OperationLeaseParcel implements Parcelable {
    public final int status;
    public final int type;
    public final String token;
    public final String message;

    public OperationLeaseParcel(int status, int type, String token, String message) {
        this.status = status;
        this.type = type;
        this.token = token;
        this.message = message;
    }

    private OperationLeaseParcel(Parcel in) {
        status = in.readInt();
        type = in.readInt();
        token = in.readString();
        message = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(status);
        dest.writeInt(type);
        dest.writeString(token);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<OperationLeaseParcel> CREATOR = new Creator<OperationLeaseParcel>() {
        @Override public OperationLeaseParcel createFromParcel(Parcel in) {
            return new OperationLeaseParcel(in);
        }
        @Override public OperationLeaseParcel[] newArray(int size) {
            return new OperationLeaseParcel[size];
        }
    };
}
