package com.kaisar.xposed.godmode.ipc.contract;

import android.os.Parcel;
import android.os.Parcelable;

/** Immutable identity returned before a client uses the 6.10 service. */
public final class ServiceIdentityParcel implements Parcelable {
    public final int protocolVersion;
    public final int buildVersionCode;
    public final String contractFingerprint;
    public final int serviceState;

    public ServiceIdentityParcel(int protocolVersion, int buildVersionCode,
                                 String contractFingerprint, int serviceState) {
        this.protocolVersion = protocolVersion;
        this.buildVersionCode = buildVersionCode;
        this.contractFingerprint = contractFingerprint;
        this.serviceState = serviceState;
    }

    private ServiceIdentityParcel(Parcel in) {
        protocolVersion = in.readInt();
        buildVersionCode = in.readInt();
        contractFingerprint = in.readString();
        serviceState = in.readInt();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(protocolVersion);
        dest.writeInt(buildVersionCode);
        dest.writeString(contractFingerprint);
        dest.writeInt(serviceState);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ServiceIdentityParcel> CREATOR =
            new Creator<ServiceIdentityParcel>() {
                @Override public ServiceIdentityParcel createFromParcel(Parcel in) {
                    return new ServiceIdentityParcel(in);
                }

                @Override public ServiceIdentityParcel[] newArray(int size) {
                    return new ServiceIdentityParcel[size];
                }
            };
}
