package com.kaisar.xposed.godmode.control;

import com.kaisar.xposed.godmode.ipc.RuleServiceContract;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Serializes global edit, maintenance and short-lived mutation authority. */
final class OperationCoordinator {
    static final long CLOSE_TIMEOUT_MS = 10_000L;

    enum State {
        IDLE,
        EDITING,
        CLOSING,
        MAINTENANCE
    }

    static final class OpenResult {
        final int status;
        final int type;
        final String token;
        final String message;
        final boolean editChanged;
        final boolean editEnabled;
        final long editRevision;

        OpenResult(int status, int type, String token, String message,
                   boolean editChanged, boolean editEnabled, long editRevision) {
            this.status = status;
            this.type = type;
            this.token = token;
            this.message = message;
            this.editChanged = editChanged;
            this.editEnabled = editEnabled;
            this.editRevision = editRevision;
        }
    }

    static final class CloseResult {
        final boolean closed;
        final boolean editChanged;
        final boolean editEnabled;
        final long editRevision;
        final String releasedEditToken;

        CloseResult(boolean closed, boolean editChanged, boolean editEnabled, long editRevision,
                    String releasedEditToken) {
            this.closed = closed;
            this.editChanged = editChanged;
            this.editEnabled = editEnabled;
            this.editRevision = editRevision;
            this.releasedEditToken = releasedEditToken;
        }
    }

    static final class EditState {
        final boolean enabled;
        final long revision;

        EditState(boolean enabled, long revision) {
            this.enabled = enabled;
            this.revision = revision;
        }
    }

    static final class Access {
        final int type;
        final String packageName;
        final int callingUid;
        final Object owner;

        Access(int type, String packageName, int callingUid, Object owner) {
            this.type = type;
            this.packageName = packageName;
            this.callingUid = callingUid;
            this.owner = owner;
        }
    }

    private final Map<String, Session> mMutations = new HashMap<>();
    private Session mEdit;
    private Session mMaintenance;
    private State mState = State.IDLE;
    private long mEditRevision;

    synchronized OpenResult open(int type, String packageName, int callingUid,
                                 boolean moduleCaller, boolean callerOwnsPackage,
                                 Object owner) {
        if (owner == null) return rejected(type, RuleServiceContract.RESULT_INVALID,
                "operation owner is required");
        if (type == RuleServiceContract.OP_EDIT) {
            if (!moduleCaller) return rejected(type, RuleServiceContract.RESULT_REJECTED,
                    "only the manager may control editing");
            if (mState != State.IDLE || !mMutations.isEmpty()) {
                return rejected(type, RuleServiceContract.RESULT_BUSY,
                        "another operation is active");
            }
            Session session = new Session(newToken(), type, null, callingUid, owner);
            mEdit = session;
            mState = State.EDITING;
            mEditRevision++;
            return accepted(session, true);
        }
        if (type == RuleServiceContract.OP_BACKUP
                || type == RuleServiceContract.OP_RESTORE) {
            if (!moduleCaller) return rejected(type, RuleServiceContract.RESULT_REJECTED,
                    "only the manager may run maintenance");
            if (mState != State.IDLE || !mMutations.isEmpty()) {
                return rejected(type, RuleServiceContract.RESULT_BUSY,
                        "editing or another operation is active");
            }
            Session session = new Session(newToken(), type, null, callingUid, owner);
            mMaintenance = session;
            mState = State.MAINTENANCE;
            return accepted(session, false);
        }
        if (type != RuleServiceContract.OP_MUTATION) {
            return rejected(type, RuleServiceContract.RESULT_INVALID, "unknown operation type");
        }
        boolean globalScope = RuleServiceContract.GLOBAL_SCOPE.equals(packageName);
        if (globalScope && !moduleCaller) {
            return rejected(type, RuleServiceContract.RESULT_REJECTED,
                    "only the manager may use global mutation scope");
        }
        boolean globalManagerMutation = moduleCaller && globalScope;
        if (!globalManagerMutation && !PackageNameValidator.isValid(packageName)) {
            return rejected(type, RuleServiceContract.RESULT_INVALID,
                    "mutation package is invalid");
        }
        if (mState == State.CLOSING || mState == State.MAINTENANCE) {
            return rejected(type, RuleServiceContract.RESULT_BUSY,
                    "editing is closing or maintenance is active");
        }
        if (!moduleCaller && (mState != State.EDITING || !callerOwnsPackage)) {
            return rejected(type, RuleServiceContract.RESULT_REJECTED,
                    "target mutation is not authorized");
        }
        Session session = new Session(newToken(), type, packageName, callingUid, owner);
        mMutations.put(session.token, session);
        return accepted(session, false);
    }

    synchronized CloseResult close(String token, Object owner, int callingUid, long timeoutMs)
            throws InterruptedException {
        Session session = findOwned(token, owner);
        if (session == null || session.callingUid != callingUid) return unchanged(false);
        if (session == mEdit) {
            if (mState == State.EDITING) mState = State.CLOSING;
            long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
            while (!mMutations.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) return unchanged(false);
                wait(remaining);
            }
            if (mEdit != session) return unchanged(true);
            return finishEditLocked();
        }
        if (session == mMaintenance) {
            if (session.persisting) return unchanged(false);
            mMaintenance = null;
            mState = State.IDLE;
            notifyAll();
            return unchanged(true);
        }
        if (session.persisting) return unchanged(false);
        mMutations.remove(token);
        CloseResult result = finishClosingIfIdleLocked();
        notifyAll();
        return new CloseResult(true, result.editChanged, result.editEnabled,
                result.editRevision, result.releasedEditToken);
    }

    synchronized CloseResult ownerDied(String token, Object owner) {
        Session session = findOwned(token, owner);
        if (session == null) return unchanged(false);
        session.ownerDead = true;
        if (session == mEdit) {
            if (mState == State.EDITING) mState = State.CLOSING;
            return finishClosingIfIdleLocked();
        }
        if (session == mMaintenance) {
            if (!session.persisting) {
                mMaintenance = null;
                mState = State.IDLE;
                notifyAll();
                return unchanged(true);
            }
            return unchanged(false);
        }
        if (!session.persisting) {
            mMutations.remove(token);
            CloseResult result = finishClosingIfIdleLocked();
            notifyAll();
            return new CloseResult(true, result.editChanged, result.editEnabled,
                    result.editRevision, result.releasedEditToken);
        }
        return unchanged(false);
    }

    synchronized Access beginPersistence(String token, Object owner, int callingUid,
                                         String packageName) {
        Session session = findOwned(token, owner);
        if (session == null || session.callingUid != callingUid || session.persisting) return null;
        if (session.type == RuleServiceContract.OP_MUTATION) {
            if (!packageName.equals(session.packageName)) return null;
        } else if (session != mMaintenance
                || session.type != RuleServiceContract.OP_RESTORE
                || !PackageNameValidator.isValid(packageName)) {
            return null;
        }
        session.persisting = true;
        return access(session);
    }

    synchronized CloseResult finishPersistence(String token, Object owner) {
        Session session = findOwned(token, owner);
        if (session == null || !session.persisting) return unchanged(false);
        session.persisting = false;
        if (session.type == RuleServiceContract.OP_MUTATION) {
            mMutations.remove(token);
            CloseResult result = finishClosingIfIdleLocked();
            notifyAll();
            return new CloseResult(true, result.editChanged, result.editEnabled,
                    result.editRevision, result.releasedEditToken);
        }
        if (session.ownerDead) {
            mMaintenance = null;
            mState = State.IDLE;
            notifyAll();
            return unchanged(true);
        }
        notifyAll();
        return unchanged(false);
    }

    synchronized EditState editState() {
        return new EditState(mState == State.EDITING || mState == State.CLOSING, mEditRevision);
    }

    synchronized State state() {
        return mState;
    }

    synchronized boolean contains(String token) {
        return find(token) != null;
    }

    synchronized void shutdown() {
        mEdit = null;
        mMaintenance = null;
        mMutations.clear();
        mState = State.IDLE;
        notifyAll();
    }

    private Session findOwned(String token, Object owner) {
        Session session = find(token);
        return session != null && session.owner == owner ? session : null;
    }

    private Session find(String token) {
        if (token == null) return null;
        if (mEdit != null && token.equals(mEdit.token)) return mEdit;
        if (mMaintenance != null && token.equals(mMaintenance.token)) return mMaintenance;
        return mMutations.get(token);
    }

    private CloseResult finishClosingIfIdleLocked() {
        if (mState == State.CLOSING && mMutations.isEmpty()) return finishEditLocked();
        return unchanged(false);
    }

    private CloseResult finishEditLocked() {
        String releasedToken = mEdit == null ? null : mEdit.token;
        mEdit = null;
        mState = State.IDLE;
        mEditRevision++;
        notifyAll();
        return new CloseResult(true, true, false, mEditRevision, releasedToken);
    }

    private OpenResult accepted(Session session, boolean editChanged) {
        return new OpenResult(RuleServiceContract.RESULT_COMMITTED, session.type, session.token,
                "lease acquired", editChanged, mState == State.EDITING, mEditRevision);
    }

    private OpenResult rejected(int type, int status, String message) {
        return new OpenResult(status, type, null, message, false,
                mState == State.EDITING || mState == State.CLOSING, mEditRevision);
    }

    private CloseResult unchanged(boolean closed) {
        return new CloseResult(closed, false,
                mState == State.EDITING || mState == State.CLOSING, mEditRevision, null);
    }

    private static Access access(Session session) {
        return new Access(session.type, session.packageName, session.callingUid, session.owner);
    }

    private static String newToken() {
        return UUID.randomUUID().toString();
    }

    private static final class Session {
        final String token;
        final int type;
        final String packageName;
        final int callingUid;
        final Object owner;
        boolean persisting;
        boolean ownerDead;

        Session(String token, int type, String packageName, int callingUid, Object owner) {
            this.token = token;
            this.type = type;
            this.packageName = packageName;
            this.callingUid = callingUid;
            this.owner = owner;
        }
    }
}
