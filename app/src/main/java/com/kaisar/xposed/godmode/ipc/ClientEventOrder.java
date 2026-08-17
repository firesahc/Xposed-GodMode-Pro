package com.kaisar.xposed.godmode.ipc;

/** Pure ordering rules shared by Binder callbacks and main-thread delivery. */
final class ClientEventOrder {
    private ClientEventOrder() { }

    static boolean isCurrent(long eventEpoch, long currentEpoch,
                             long eventVersion, long currentVersion) {
        return eventEpoch == currentEpoch && eventVersion >= currentVersion;
    }
}
