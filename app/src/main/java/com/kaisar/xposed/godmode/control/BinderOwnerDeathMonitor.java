package com.kaisar.xposed.godmode.control;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.atomic.AtomicBoolean;

/** Production {@link OwnerDeathMonitor} backed by Binder death recipients. */
final class BinderOwnerDeathMonitor implements OwnerDeathMonitor {
    @Override public Registration link(Object owner, Runnable callback)
            throws OwnerAlreadyDeadException {
        if (!(owner instanceof IBinder)) {
            throw new IllegalArgumentException("lease owner must be an IBinder");
        }
        IBinder binder = (IBinder) owner;
        IBinder.DeathRecipient recipient = callback::run;
        try {
            binder.linkToDeath(recipient, 0);
        } catch (RemoteException e) {
            throw new OwnerAlreadyDeadException("lease owner already died", e);
        }
        AtomicBoolean linked = new AtomicBoolean(true);
        return () -> {
            if (!linked.compareAndSet(true, false)) return;
            try {
                binder.unlinkToDeath(recipient, 0);
            } catch (RuntimeException ignored) {
                // A concurrently dead Binder is already unlinked for lifecycle purposes.
            }
        };
    }
}
