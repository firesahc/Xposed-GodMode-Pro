package com.kaisar.xposed.godmode.ipc.contract;

/** Client-owned Binder identity used to release a write lease on process death. */
oneway interface ILeaseOwner {
    void onLeaseRevoked(int reason);
}
