package com.kaisar.xposed.godmode.control;

import com.kaisar.xposed.godmode.ipc.RuleServiceContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns short operation leases, owner-death registration and persistence completion. */
final class OperationLeaseController {
    interface Listener {
        void onEditRevisionClosed(long editRevision);
        void onEditTransition(EditTransition transition);
        void onOwnerDied(LeaseInfo lease);
    }

    static final class EditTransition {
        final boolean enabled;
        final long revision;
        final long closedEditRevision;

        EditTransition(boolean enabled, long revision, long closedEditRevision) {
            this.enabled = enabled;
            this.revision = revision;
            this.closedEditRevision = closedEditRevision;
        }
    }

    static final class LeaseInfo {
        final String token;
        final int type;
        final String packageName;
        final int callingUid;

        LeaseInfo(String token, int type, String packageName, int callingUid) {
            this.token = token;
            this.type = type;
            this.packageName = packageName;
            this.callingUid = callingUid;
        }
    }

    static final class CloseOutcome {
        final LeaseInfo lease;
        final OperationCoordinator.CloseResult result;

        CloseOutcome(LeaseInfo lease, OperationCoordinator.CloseResult result) {
            this.lease = lease;
            this.result = result;
        }
    }

    final class PersistencePermit implements AutoCloseable {
        private final String mToken;
        private final Object mOwner;
        private final OperationCoordinator.Access mAccess;
        private final AtomicBoolean mFinishStarted = new AtomicBoolean();
        private final CountDownLatch mFinishCompleted = new CountDownLatch(1);
        private volatile OperationCoordinator.CloseResult mResult;

        private PersistencePermit(String token, Object owner,
                                  OperationCoordinator.Access access) {
            mToken = token;
            mOwner = owner;
            mAccess = access;
        }

        OperationCoordinator.Access access() {
            return mAccess;
        }

        OperationCoordinator.CloseResult finish() {
            if (mFinishStarted.compareAndSet(false, true)) {
                try {
                    mResult = finishPermit(mToken, mOwner, mAccess);
                } finally {
                    permitFinished();
                    mFinishCompleted.countDown();
                }
                return mResult;
            }
            boolean interrupted = false;
            while (true) {
                try {
                    mFinishCompleted.await();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return mResult;
        }

        @Override public void close() {
            finish();
        }
    }

    private enum OpenPhase {
        LINKING,
        PUBLISHING,
        ACTIVE,
        DEAD
    }

    private static final class LeaseEntry {
        final LeaseInfo info;
        final Object owner;
        OpenPhase phase = OpenPhase.LINKING;
        boolean deathPending;
        OwnerDeathMonitor.Registration deathRegistration;

        LeaseEntry(LeaseInfo info, Object owner) {
            this.info = info;
            this.owner = owner;
        }
    }

    private final Object mRegistryLock = new Object();
    private final Map<String, LeaseEntry> mLeases = new HashMap<>();
    private final OperationCoordinator mCoordinator;
    private final OwnerDeathMonitor mDeathMonitor;
    private final Listener mListener;
    private boolean mShutdown;
    private int mPermitAcquisitions;
    private int mActivePermits;

    OperationLeaseController(OwnerDeathMonitor deathMonitor, Listener listener) {
        this(new OperationCoordinator(), deathMonitor, listener);
    }

    OperationLeaseController(OperationCoordinator coordinator, OwnerDeathMonitor deathMonitor,
                             Listener listener) {
        if (coordinator == null || deathMonitor == null || listener == null) {
            throw new IllegalArgumentException("lease controller dependencies are required");
        }
        mCoordinator = coordinator;
        mDeathMonitor = deathMonitor;
        mListener = listener;
    }

    OperationCoordinator.OpenResult open(int type, String packageName, int callingUid,
                                         boolean moduleCaller, boolean callerOwnsPackage,
                                         Object owner) {
        boolean shutDown;
        synchronized (mRegistryLock) {
            shutDown = mShutdown;
        }
        if (shutDown) return unavailable(type, "operation controller is shut down");
        OperationCoordinator.OpenResult opened = mCoordinator.open(type, packageName, callingUid,
                moduleCaller, callerOwnsPackage, owner);
        if (opened.status != RuleServiceContract.RESULT_COMMITTED) return opened;

        LeaseEntry entry = new LeaseEntry(
                new LeaseInfo(opened.token, type, packageName, callingUid), owner);
        boolean shutDownBeforeRegistration;
        synchronized (mRegistryLock) {
            shutDownBeforeRegistration = mShutdown;
            if (!shutDownBeforeRegistration) mLeases.put(opened.token, entry);
        }
        if (shutDownBeforeRegistration) {
            return rollbackOpen(opened, entry, "operation controller is shut down");
        }

        OwnerDeathMonitor.Registration registration;
        try {
            registration = mDeathMonitor.link(owner,
                    () -> recordOwnerDeath(opened.token, owner));
        } catch (OwnerDeathMonitor.OwnerAlreadyDeadException | RuntimeException e) {
            removeEntry(entry);
            return rollbackOpen(opened, entry, "operation owner already died");
        }

        boolean publishOpen;
        synchronized (mRegistryLock) {
            if (mLeases.get(opened.token) != entry || mShutdown || entry.deathPending) {
                entry.phase = OpenPhase.DEAD;
                mLeases.remove(opened.token, entry);
                publishOpen = false;
            } else {
                entry.deathRegistration = registration;
                entry.phase = OpenPhase.PUBLISHING;
                publishOpen = true;
            }
        }
        if (!publishOpen) {
            registration.unlink();
            return rollbackOpen(opened, entry, "operation owner already died");
        }

        publishOpened(opened);

        boolean ownerDiedWhilePublishing;
        boolean diedWhilePublishing;
        boolean shutDownWhilePublishing;
        synchronized (mRegistryLock) {
            shutDownWhilePublishing = mShutdown;
            ownerDiedWhilePublishing = entry.deathPending;
            diedWhilePublishing = ownerDiedWhilePublishing || shutDownWhilePublishing;
            if (diedWhilePublishing) {
                entry.phase = OpenPhase.DEAD;
                mLeases.remove(opened.token, entry);
            } else {
                entry.phase = OpenPhase.ACTIVE;
            }
        }
        if (diedWhilePublishing) {
            registration.unlink();
            OperationCoordinator.CloseResult result = mCoordinator.ownerDied(
                    entry.info.token, entry.owner);
            completeTransition(result);
            if (ownerDiedWhilePublishing) publishOwnerDied(entry.info);
            return unavailable(type, shutDownWhilePublishing
                    ? "operation controller is shut down" : "operation owner already died");
        }
        return opened;
    }

    CloseOutcome close(String token, Object owner, int callingUid, long timeoutMs)
            throws InterruptedException {
        LeaseEntry entry = entry(token);
        if (entry == null) return new CloseOutcome(null, unchanged(false));
        OperationCoordinator.CloseResult result = mCoordinator.close(
                token, owner, callingUid, timeoutMs);
        if (result.closed) detach(entry, true);
        completeTransition(result);
        return new CloseOutcome(entry.info, result);
    }

    PersistencePermit acquirePersistence(String token, Object owner, int callingUid,
                                         String packageName) {
        synchronized (mRegistryLock) {
            if (mShutdown || !mLeases.containsKey(token)) return null;
            mPermitAcquisitions++;
        }
        OperationCoordinator.Access access = null;
        try {
            access = mCoordinator.beginPersistence(token, owner, callingUid, packageName);
            return access == null ? null : new PersistencePermit(token, owner, access);
        } finally {
            synchronized (mRegistryLock) {
                mPermitAcquisitions--;
                if (access != null) mActivePermits++;
                mRegistryLock.notifyAll();
            }
        }
    }

    LeaseInfo leaseInfo(String token) {
        LeaseEntry entry = entry(token);
        return entry == null ? null : entry.info;
    }

    OperationCoordinator.EditState editState() {
        return mCoordinator.editState();
    }

    void shutdownAndDrain() {
        List<LeaseEntry> entries;
        synchronized (mRegistryLock) {
            if (!mShutdown) {
                mShutdown = true;
                entries = new ArrayList<>(mLeases.values());
                mLeases.clear();
                for (LeaseEntry entry : entries) entry.phase = OpenPhase.DEAD;
            } else {
                entries = new ArrayList<>();
            }
        }
        for (LeaseEntry entry : entries) unlink(entry);
        boolean interrupted = false;
        synchronized (mRegistryLock) {
            while (mPermitAcquisitions != 0 || mActivePermits != 0) {
                try {
                    mRegistryLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        mCoordinator.shutdown();
        if (interrupted) Thread.currentThread().interrupt();
    }

    private OperationCoordinator.CloseResult finishPermit(
            String token, Object owner, OperationCoordinator.Access access) {
        OperationCoordinator.CloseResult result = mCoordinator.finishPersistence(token, owner);
        if (access.type == RuleServiceContract.OP_MUTATION) {
            LeaseEntry entry = entry(token);
            if (entry != null) detach(entry, true);
        }
        completeTransition(result);
        return result;
    }

    private void permitFinished() {
        synchronized (mRegistryLock) {
            if (mActivePermits <= 0) {
                throw new IllegalStateException("operation permit accounting underflow");
            }
            mActivePermits--;
            mRegistryLock.notifyAll();
        }
    }

    private void recordOwnerDeath(String token, Object owner) {
        LeaseEntry entry;
        synchronized (mRegistryLock) {
            entry = mLeases.get(token);
            if (entry == null || entry.owner != owner || entry.phase == OpenPhase.DEAD) return;
            if (entry.phase == OpenPhase.LINKING || entry.phase == OpenPhase.PUBLISHING) {
                entry.deathPending = true;
                return;
            }
            entry.phase = OpenPhase.DEAD;
            mLeases.remove(token, entry);
        }
        handleOwnerDeath(entry);
    }

    private void handleOwnerDeath(LeaseEntry entry) {
        OperationCoordinator.CloseResult result = mCoordinator.ownerDied(
                entry.info.token, entry.owner);
        completeTransition(result);
        publishOwnerDied(entry.info);
    }

    private OperationCoordinator.OpenResult rollbackOpen(OperationCoordinator.OpenResult opened,
                                                         LeaseEntry entry, String message) {
        OperationCoordinator.CloseResult result = mCoordinator.ownerDied(opened.token, entry.owner);
        completeTransition(result);
        return new OperationCoordinator.OpenResult(RuleServiceContract.RESULT_BUSY, opened.type,
                null, message, false, result.editEnabled, result.editRevision);
    }

    private void removeEntry(LeaseEntry entry) {
        synchronized (mRegistryLock) {
            entry.phase = OpenPhase.DEAD;
            mLeases.remove(entry.info.token, entry);
        }
    }

    private LeaseEntry entry(String token) {
        if (token == null) return null;
        synchronized (mRegistryLock) {
            return mLeases.get(token);
        }
    }

    private void detach(LeaseEntry entry, boolean unlink) {
        boolean removed;
        synchronized (mRegistryLock) {
            removed = mLeases.remove(entry.info.token, entry);
            if (removed) entry.phase = OpenPhase.DEAD;
        }
        if (removed && unlink) unlink(entry);
    }

    private static void unlink(LeaseEntry entry) {
        OwnerDeathMonitor.Registration registration = entry.deathRegistration;
        if (registration != null) registration.unlink();
    }

    private void publishOpened(OperationCoordinator.OpenResult result) {
        if (!result.editChanged) return;
        try {
            mListener.onEditTransition(new EditTransition(
                    result.editEnabled, result.editRevision, 0L));
        } catch (RuntimeException ignored) { }
    }

    private void completeTransition(OperationCoordinator.CloseResult result) {
        if (result != null && result.releasedEditToken != null) {
            LeaseEntry releasedEdit = entry(result.releasedEditToken);
            if (releasedEdit != null) detach(releasedEdit, true);
        }
        if (result == null || !result.editChanged) return;
        if (!result.editEnabled && result.closedEditRevision > 0L) {
            try {
                mListener.onEditRevisionClosed(result.closedEditRevision);
            } catch (RuntimeException ignored) { }
        }
        try {
            mListener.onEditTransition(new EditTransition(
                    result.editEnabled, result.editRevision, result.closedEditRevision));
        } catch (RuntimeException ignored) { }
    }

    private void publishOwnerDied(LeaseInfo lease) {
        try {
            mListener.onOwnerDied(lease);
        } catch (RuntimeException ignored) { }
    }

    private OperationCoordinator.OpenResult unavailable(int type, String message) {
        OperationCoordinator.EditState state = mCoordinator.editState();
        return new OperationCoordinator.OpenResult(RuleServiceContract.RESULT_BUSY, type, null,
                message, false, state.enabled, state.revision);
    }

    private OperationCoordinator.CloseResult unchanged(boolean closed) {
        OperationCoordinator.EditState state = mCoordinator.editState();
        return new OperationCoordinator.CloseResult(closed, false, state.enabled,
                state.revision, 0L, null);
    }
}
