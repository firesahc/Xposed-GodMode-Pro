package com.kaisar.xposed.godmode.ipc;

/** Owns the client-side projection of the authoritative edit session. */
final class ClientEditState {
    private String mLeaseToken;
    private boolean mEnabled;
    private boolean mKnown;
    private boolean mClosing;
    private long mRevision = -1L;

    synchronized void reset() {
        mLeaseToken = null;
        mEnabled = false;
        mKnown = false;
        mClosing = false;
        mRevision = -1L;
    }

    synchronized boolean canRequestEnable() {
        return !mClosing;
    }

    synchronized String leaseToken() {
        return mLeaseToken;
    }

    synchronized void setLeaseToken(String token) {
        mLeaseToken = token;
        if (token != null) mClosing = false;
    }

    synchronized void clearStaleDisabledLease() {
        if (mLeaseToken != null && mKnown && !mEnabled) mLeaseToken = null;
    }

    synchronized void markClosing(String token) {
        if (token != null && token.equals(mLeaseToken)) mClosing = true;
    }

    synchronized boolean accept(boolean enabled, long revision) {
        if (revision < mRevision) return false;
        mRevision = revision;
        mEnabled = enabled;
        mKnown = true;
        if (!enabled) {
            mLeaseToken = null;
            mClosing = false;
        }
        return true;
    }

    synchronized boolean isEnabled() {
        return mKnown && mEnabled;
    }

    synchronized boolean isKnown() {
        return mKnown;
    }

    synchronized boolean isClosing() {
        return mClosing;
    }

    synchronized long revision() {
        return mRevision;
    }
}
