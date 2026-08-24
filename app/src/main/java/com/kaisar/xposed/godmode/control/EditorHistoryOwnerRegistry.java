package com.kaisar.xposed.godmode.control;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns process-scoped editor history identities and revision-scoped in-flight guards.
 * External death-monitor and repository callbacks are always invoked outside the registry lock.
 */
final class EditorHistoryOwnerRegistry {
    interface ReleaseSink {
        void releaseScope(RuleRepository.UndoScope scope);
        void releaseOwner(String ownerId, int callingUid);
    }

    final class OwnerLease implements AutoCloseable {
        private final OwnerRecord mOwner;
        private final EpochRecord mEpoch;
        private final String mPackageName;
        private final AtomicBoolean mClosed = new AtomicBoolean();

        private OwnerLease(OwnerRecord owner, EpochRecord epoch, String packageName) {
            mOwner = owner;
            mEpoch = epoch;
            mPackageName = packageName;
        }

        RuleRepository.UndoScope scope() {
            return new RuleRepository.UndoScope(mOwner.ownerId, mOwner.callingUid,
                    mPackageName, mEpoch.editRevision);
        }

        boolean isActive() {
            synchronized (mLock) {
                return !mClosed.get() && !mShutdown && !mOwner.dead && !mEpoch.revoked
                        && mOwner.epochs.get(mEpoch.editRevision) == mEpoch;
            }
        }

        @Override public void close() {
            if (!mClosed.compareAndSet(false, true)) return;
            releaseLease(mOwner, mEpoch);
        }
    }

    private static final class OwnerRecord {
        final Object owner;
        final int callingUid;
        final String ownerId = UUID.randomUUID().toString();
        final Map<Long, EpochRecord> epochs = new HashMap<>();
        OwnerDeathMonitor.Registration deathRegistration;
        boolean published;
        boolean dead;
        boolean unlinkScheduled;
        boolean ownerReleaseScheduled;
        int inFlight;

        OwnerRecord(Object owner, int callingUid) {
            this.owner = owner;
            this.callingUid = callingUid;
        }
    }

    private static final class EpochRecord {
        final long editRevision;
        final Set<String> packages = new HashSet<>();
        boolean revoked;
        boolean scopeReleaseScheduled;
        int inFlight;

        EpochRecord(long editRevision) {
            this.editRevision = editRevision;
        }
    }

    private interface ExternalAction {
        void run();
    }

    private final Object mLock = new Object();
    private final IdentityHashMap<Object, OwnerRecord> mOwners = new IdentityHashMap<>();
    private final OwnerDeathMonitor mDeathMonitor;
    private final ReleaseSink mReleaseSink;
    private boolean mShutdown;
    private long mHighestClosedRevision;
    private int mLinking;
    private int mActiveLeases;
    private int mExternalActions;

    EditorHistoryOwnerRegistry(OwnerDeathMonitor deathMonitor, ReleaseSink releaseSink) {
        if (deathMonitor == null || releaseSink == null) {
            throw new IllegalArgumentException("history registry dependencies are required");
        }
        mDeathMonitor = deathMonitor;
        mReleaseSink = releaseSink;
    }

    OwnerLease acquireOrCreate(Object owner, int callingUid, String packageName,
                               long editRevision) {
        return acquire(owner, callingUid, packageName, editRevision, true);
    }

    OwnerLease acquireExisting(Object owner, int callingUid, String packageName,
                               long editRevision) {
        return acquire(owner, callingUid, packageName, editRevision, false);
    }

    void closeRevision(long editRevision) {
        if (editRevision <= 0L) return;
        List<ExternalAction> actions = new ArrayList<>();
        synchronized (mLock) {
            if (editRevision > mHighestClosedRevision) mHighestClosedRevision = editRevision;
            for (OwnerRecord owner : mOwners.values()) {
                EpochRecord epoch = owner.epochs.get(editRevision);
                if (epoch == null || epoch.revoked) continue;
                epoch.revoked = true;
                scheduleScopeReleaseIfReadyLocked(owner, epoch, actions);
            }
        }
        runActions(actions);
    }

    void shutdownAndDrain() {
        List<ExternalAction> actions = new ArrayList<>();
        boolean interrupted = false;
        synchronized (mLock) {
            if (!mShutdown) {
                mShutdown = true;
                List<OwnerRecord> owners = new ArrayList<>(mOwners.values());
                mOwners.clear();
                for (OwnerRecord owner : owners) {
                    owner.dead = true;
                    for (EpochRecord epoch : owner.epochs.values()) epoch.revoked = true;
                    scheduleUnlinkLocked(owner, actions);
                    scheduleOwnerReleaseIfReadyLocked(owner, actions);
                }
            }
        }
        runActions(actions);

        synchronized (mLock) {
            while (mLinking != 0 || mActiveLeases != 0 || mExternalActions != 0) {
                try {
                    mLock.wait();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private OwnerLease acquire(Object ownerObject, int callingUid, String packageName,
                               long editRevision, boolean createEpoch) {
        if (ownerObject == null || packageName == null || packageName.isEmpty()
                || editRevision <= 0L) return null;

        synchronized (mLock) {
            if (mShutdown || editRevision <= mHighestClosedRevision) return null;
            OwnerRecord existing = mOwners.get(ownerObject);
            if (existing != null) {
                return existing.callingUid == callingUid
                        ? acquireEpochLocked(existing, packageName, editRevision, createEpoch)
                        : null;
            }
            if (!createEpoch) return null;
            mLinking++;
        }

        OwnerRecord candidate = new OwnerRecord(ownerObject, callingUid);
        OwnerDeathMonitor.Registration registration;
        try {
            registration = mDeathMonitor.link(ownerObject, () -> ownerDied(candidate));
        } catch (OwnerDeathMonitor.OwnerAlreadyDeadException | RuntimeException e) {
            synchronized (mLock) {
                mLinking--;
                mLock.notifyAll();
            }
            return null;
        }

        List<ExternalAction> actions = new ArrayList<>();
        OwnerLease lease = null;
        synchronized (mLock) {
            mLinking--;
            candidate.deathRegistration = registration;
            OwnerRecord existing = mOwners.get(ownerObject);
            if (mShutdown || editRevision <= mHighestClosedRevision || candidate.dead) {
                scheduleUnlinkLocked(candidate, actions);
            } else if (existing != null) {
                scheduleUnlinkLocked(candidate, actions);
                if (!existing.dead && existing.callingUid == callingUid) {
                    lease = acquireEpochLocked(existing, packageName, editRevision, true);
                }
            } else {
                candidate.published = true;
                mOwners.put(ownerObject, candidate);
                lease = acquireEpochLocked(candidate, packageName, editRevision, true);
            }
            mLock.notifyAll();
        }
        runActions(actions);
        return lease;
    }

    private OwnerLease acquireEpochLocked(OwnerRecord owner, String packageName,
                                          long editRevision, boolean create) {
        if (owner.dead || mShutdown || editRevision <= mHighestClosedRevision) return null;
        EpochRecord epoch = owner.epochs.get(editRevision);
        if (epoch == null) {
            if (!create) return null;
            epoch = new EpochRecord(editRevision);
            owner.epochs.put(editRevision, epoch);
        } else if (epoch.revoked) {
            return null;
        }
        if (!create && !epoch.packages.contains(packageName)) return null;
        epoch.packages.add(packageName);
        epoch.inFlight++;
        owner.inFlight++;
        mActiveLeases++;
        return new OwnerLease(owner, epoch, packageName);
    }

    private void ownerDied(OwnerRecord owner) {
        List<ExternalAction> actions = new ArrayList<>();
        synchronized (mLock) {
            if (owner.dead) return;
            owner.dead = true;
            if (owner.published && mOwners.get(owner.owner) == owner) {
                mOwners.remove(owner.owner);
            }
            for (EpochRecord epoch : owner.epochs.values()) epoch.revoked = true;
            scheduleUnlinkLocked(owner, actions);
            scheduleOwnerReleaseIfReadyLocked(owner, actions);
        }
        runActions(actions);
    }

    private void releaseLease(OwnerRecord owner, EpochRecord epoch) {
        List<ExternalAction> actions = new ArrayList<>();
        synchronized (mLock) {
            if (epoch.inFlight <= 0 || owner.inFlight <= 0 || mActiveLeases <= 0) {
                throw new IllegalStateException("history owner lease accounting underflow");
            }
            epoch.inFlight--;
            owner.inFlight--;
            mActiveLeases--;
            if (owner.dead) {
                scheduleOwnerReleaseIfReadyLocked(owner, actions);
            } else {
                scheduleScopeReleaseIfReadyLocked(owner, epoch, actions);
            }
            mLock.notifyAll();
        }
        runActions(actions);
    }

    private void scheduleScopeReleaseIfReadyLocked(OwnerRecord owner, EpochRecord epoch,
                                                   List<ExternalAction> actions) {
        if (!epoch.revoked || epoch.inFlight != 0 || epoch.scopeReleaseScheduled || owner.dead) {
            return;
        }
        epoch.scopeReleaseScheduled = true;
        owner.epochs.remove(epoch.editRevision, epoch);
        for (String packageName : epoch.packages) {
            RuleRepository.UndoScope scope = new RuleRepository.UndoScope(
                    owner.ownerId, owner.callingUid, packageName, epoch.editRevision);
            queueActionLocked(actions, () -> mReleaseSink.releaseScope(scope));
        }
    }

    private void scheduleOwnerReleaseIfReadyLocked(OwnerRecord owner,
                                                   List<ExternalAction> actions) {
        if (!owner.published || !owner.dead || owner.inFlight != 0
                || owner.ownerReleaseScheduled) return;
        owner.ownerReleaseScheduled = true;
        queueActionLocked(actions,
                () -> mReleaseSink.releaseOwner(owner.ownerId, owner.callingUid));
    }

    private void scheduleUnlinkLocked(OwnerRecord owner, List<ExternalAction> actions) {
        if (owner.unlinkScheduled || owner.deathRegistration == null) return;
        owner.unlinkScheduled = true;
        OwnerDeathMonitor.Registration registration = owner.deathRegistration;
        queueActionLocked(actions, registration::unlink);
    }

    private void queueActionLocked(List<ExternalAction> actions, ExternalAction action) {
        mExternalActions++;
        actions.add(action);
    }

    private void runActions(List<ExternalAction> actions) {
        for (ExternalAction action : actions) {
            try {
                action.run();
            } catch (RuntimeException ignored) {
                // Lifecycle cleanup is best-effort per action; remaining actions must still run.
            } finally {
                synchronized (mLock) {
                    mExternalActions--;
                    mLock.notifyAll();
                }
            }
        }
    }
}
