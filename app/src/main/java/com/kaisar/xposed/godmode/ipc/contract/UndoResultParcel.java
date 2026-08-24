package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Result of an authoritative undo transaction. */
public final class UndoResultParcel implements Parcelable {
    public final int status;
    public final String requestId;
    public final String packageName;
    public final long generation;
    public final UndoStateParcel undoState;
    public final String message;

    public UndoResultParcel(int status, String requestId, String packageName, long generation,
                            UndoStateParcel undoState, String message) {
        this.status = status;
        this.requestId = requestId;
        this.packageName = packageName;
        this.generation = generation;
        this.undoState = undoState;
        this.message = message;
    }

    private UndoResultParcel(Parcel in) {
        status = in.readInt();
        requestId = in.readString();
        packageName = in.readString();
        generation = in.readLong();
        undoState = in.readParcelable(UndoStateParcel.class.getClassLoader());
        message = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(status);
        dest.writeString(requestId);
        dest.writeString(packageName);
        dest.writeLong(generation);
        dest.writeParcelable(undoState, flags);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<UndoResultParcel> CREATOR = new Creator<UndoResultParcel>() {
        @Override public UndoResultParcel createFromParcel(Parcel in) {
            return new UndoResultParcel(in);
        }

        @Override public UndoResultParcel[] newArray(int size) {
            return new UndoResultParcel[size];
        }
    };
}
