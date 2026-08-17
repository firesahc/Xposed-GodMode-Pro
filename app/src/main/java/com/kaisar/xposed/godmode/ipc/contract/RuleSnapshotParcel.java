package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.SharedMemory;

/** A validated snapshot handle; the payload remains the existing flat JSON. */
public final class RuleSnapshotParcel implements Parcelable {
    public final int status;
    public final String packageName;
    public final long generation;
    public final int payloadLength;
    public final String sha256;
    public final SharedMemory memory;

    public RuleSnapshotParcel(int status, String packageName, long generation,
                              int payloadLength, String sha256, SharedMemory memory) {
        this.status = status;
        this.packageName = packageName;
        this.generation = generation;
        this.payloadLength = payloadLength;
        this.sha256 = sha256;
        this.memory = memory;
    }

    private RuleSnapshotParcel(Parcel in) {
        status = in.readInt();
        packageName = in.readString();
        generation = in.readLong();
        payloadLength = in.readInt();
        sha256 = in.readString();
        memory = in.readParcelable(SharedMemory.class.getClassLoader());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(status);
        dest.writeString(packageName);
        dest.writeLong(generation);
        dest.writeInt(payloadLength);
        dest.writeString(sha256);
        dest.writeParcelable(memory, flags | Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
    }

    @Override public int describeContents() {
        return memory != null ? CONTENTS_FILE_DESCRIPTOR : 0;
    }

    public static final Creator<RuleSnapshotParcel> CREATOR = new Creator<RuleSnapshotParcel>() {
        @Override public RuleSnapshotParcel createFromParcel(Parcel in) {
            return new RuleSnapshotParcel(in);
        }
        @Override public RuleSnapshotParcel[] newArray(int size) {
            return new RuleSnapshotParcel[size];
        }
    };
}
