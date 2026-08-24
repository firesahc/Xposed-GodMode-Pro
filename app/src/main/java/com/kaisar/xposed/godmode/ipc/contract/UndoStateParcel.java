package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Authoritative projection of one editor owner's bounded undo history. */
public final class UndoStateParcel implements Parcelable {
    public final int status;
    public final String packageName;
    public final long editRevision;
    public final long historyRevision;
    public final int depth;
    public final long topSequence;
    public final String topSourceRequestId;
    public final String message;

    public UndoStateParcel(int status, String packageName, long editRevision,
                           long historyRevision, int depth, long topSequence,
                           String topSourceRequestId, String message) {
        this.status = status;
        this.packageName = packageName;
        this.editRevision = editRevision;
        this.historyRevision = historyRevision;
        this.depth = depth;
        this.topSequence = topSequence;
        this.topSourceRequestId = topSourceRequestId;
        this.message = message;
    }

    private UndoStateParcel(Parcel in) {
        status = in.readInt();
        packageName = in.readString();
        editRevision = in.readLong();
        historyRevision = in.readLong();
        depth = in.readInt();
        topSequence = in.readLong();
        topSourceRequestId = in.readString();
        message = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(status);
        dest.writeString(packageName);
        dest.writeLong(editRevision);
        dest.writeLong(historyRevision);
        dest.writeInt(depth);
        dest.writeLong(topSequence);
        dest.writeString(topSourceRequestId);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<UndoStateParcel> CREATOR = new Creator<UndoStateParcel>() {
        @Override public UndoStateParcel createFromParcel(Parcel in) {
            return new UndoStateParcel(in);
        }

        @Override public UndoStateParcel[] newArray(int size) {
            return new UndoStateParcel[size];
        }
    };
}
