package com.kaisar.xposed.godmode.ipc.contract;

/** Lightweight invalidation callbacks. The snapshot is read through IRuleService. */
oneway interface IRuleObserver {
    void onEditModeChanged(boolean enable, long editRevision);
    void onRulesInvalidated(String packageName, long generation);
}
