package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Compare-and-set request for undoing the latest authoritative editor mutation. */
public final class UndoRequestParcel implements Parcelable {
    public final String requestId;
    public final String leaseToken;
    public final String packageName;
    public final long expectedEditRevision;
    public final long expectedHistoryRevision;
    public final long expectedTopSequence;

    public UndoRequestParcel(String requestId, String leaseToken, String packageName,
                             long expectedEditRevision, long expectedHistoryRevision,
                             long expectedTopSequence) {
        this.requestId = requestId;
        this.leaseToken = leaseToken;
        this.packageName = packageName;
        this.expectedEditRevision = expectedEditRevision;
        this.expectedHistoryRevision = expectedHistoryRevision;
        this.expectedTopSequence = expectedTopSequence;
    }

    private UndoRequestParcel(Parcel in) {
        requestId = in.readString();
        leaseToken = in.readString();
        packageName = in.readString();
        expectedEditRevision = in.readLong();
        expectedHistoryRevision = in.readLong();
        expectedTopSequence = in.readLong();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(requestId);
        dest.writeString(leaseToken);
        dest.writeString(packageName);
        dest.writeLong(expectedEditRevision);
        dest.writeLong(expectedHistoryRevision);
        dest.writeLong(expectedTopSequence);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<UndoRequestParcel> CREATOR = new Creator<UndoRequestParcel>() {
        @Override public UndoRequestParcel createFromParcel(Parcel in) {
            return new UndoRequestParcel(in);
        }

        @Override public UndoRequestParcel[] newArray(int size) {
            return new UndoRequestParcel[size];
        }
    };
}
