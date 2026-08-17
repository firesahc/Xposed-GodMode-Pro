package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Initial observer state captured when a subscription is registered. */
public final class ObserverRegistrationParcel implements Parcelable {
    public final int status;
    public final boolean editEnabled;
    public final long editRevision;
    public final long ruleGeneration;
    public final String message;

    public ObserverRegistrationParcel(int status, boolean editEnabled, long editRevision,
                                      long ruleGeneration, String message) {
        this.status = status;
        this.editEnabled = editEnabled;
        this.editRevision = editRevision;
        this.ruleGeneration = ruleGeneration;
        this.message = message;
    }

    private ObserverRegistrationParcel(Parcel in) {
        status = in.readInt();
        editEnabled = in.readInt() != 0;
        editRevision = in.readLong();
        ruleGeneration = in.readLong();
        message = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(status);
        dest.writeInt(editEnabled ? 1 : 0);
        dest.writeLong(editRevision);
        dest.writeLong(ruleGeneration);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ObserverRegistrationParcel> CREATOR =
            new Creator<ObserverRegistrationParcel>() {
                @Override public ObserverRegistrationParcel createFromParcel(Parcel in) {
                    return new ObserverRegistrationParcel(in);
                }

                @Override public ObserverRegistrationParcel[] newArray(int size) {
                    return new ObserverRegistrationParcel[size];
                }
            };
}
