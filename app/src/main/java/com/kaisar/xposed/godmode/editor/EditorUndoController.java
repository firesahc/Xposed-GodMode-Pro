package com.kaisar.xposed.godmode.editor;

import com.kaisar.xposed.godmode.ipc.RuleServiceContract;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;

import java.util.Objects;

/**
 * Main-thread state holder for the authoritative editor undo projection.
 *
 * <p>This class deliberately stores no rules or undo entries. The system_server journal remains
 * the only history; the projection is used solely for UI state and compare-and-set requests.</p>
 */
public final class EditorUndoController {

    public static final long INVALID_SCOPE = -1L;

    private static final int OP_NONE = 0;
    private static final int OP_REFRESH = 1;
    private static final int OP_FORWARD = 2;
    private static final int OP_UNDO = 3;

    public interface Listener {
        void onAvailabilityChanged(boolean available);
    }

    private Listener mListener;
    private String mPackageName;
    private UndoStateParcel mProjection;
    private long mScopeGeneration;
    private long mInFlightScope = INVALID_SCOPE;
    private int mInFlightOperation = OP_NONE;
    private boolean mLastAvailability;

    public void setListener(Listener listener) {
        mListener = listener;
        dispatchAvailability(true);
    }

    /** Changes package scope without discarding history when only the Activity changes. */
    public void bindPackage(String packageName) {
        if (Objects.equals(mPackageName, packageName)) return;
        mPackageName = packageName;
        mScopeGeneration++;
        mProjection = null;
        clearInFlight();
        dispatchAvailability(true);
    }

    public long beginRefresh() {
        if (isOperationInFlight()) return INVALID_SCOPE;
        beginOperation(OP_REFRESH);
        dispatchAvailability(false);
        return mScopeGeneration;
    }

    public boolean completeRefresh(long scopeGeneration, UndoStateParcel state) {
        if (!acceptCompletion(scopeGeneration, OP_REFRESH)) return false;
        if (state == null) {
            dispatchAvailability(false);
            return false;
        }
        if (isTransientFailure(state.status)) {
            dispatchAvailability(false);
            return false;
        }
        project(state);
        return state.status == RuleServiceContract.RESULT_COMMITTED;
    }

    public long beginForwardMutation() {
        if (isOperationInFlight()) return INVALID_SCOPE;
        beginOperation(OP_FORWARD);
        dispatchAvailability(false);
        return mScopeGeneration;
    }

    public boolean completeForwardMutation(long scopeGeneration, UndoStateParcel state) {
        if (!acceptCompletion(scopeGeneration, OP_FORWARD)) return false;
        if (state != null) {
            project(state);
        } else {
            dispatchAvailability(false);
        }
        return true;
    }

    public boolean failForwardMutation(long scopeGeneration) {
        if (!acceptCompletion(scopeGeneration, OP_FORWARD)) return false;
        dispatchAvailability(false);
        return true;
    }

    /** Returns the exact projection to send in the authoritative CAS request. */
    public UndoAttempt beginUndo() {
        if (!isUndoAvailable()) return null;
        beginOperation(OP_UNDO);
        dispatchAvailability(false);
        return new UndoAttempt(mScopeGeneration, mProjection);
    }

    public boolean completeUndo(long scopeGeneration, UndoStateParcel state) {
        if (!acceptCompletion(scopeGeneration, OP_UNDO)) return false;
        if (state != null) {
            project(state);
        } else {
            dispatchAvailability(false);
        }
        return true;
    }

    public boolean failUndo(long scopeGeneration, UndoStateParcel refreshedState) {
        if (!acceptCompletion(scopeGeneration, OP_UNDO)) return false;
        if (refreshedState != null) {
            project(refreshedState);
        } else {
            dispatchAvailability(false);
        }
        return true;
    }

    public UndoStateParcel getProjection() {
        return mProjection;
    }

    public boolean isBoundTo(String packageName) {
        return Objects.equals(mPackageName, packageName);
    }

    public boolean isUndoAvailable() {
        return !isOperationInFlight()
                && mProjection != null
                && mProjection.depth > 0
                && mProjection.topSequence > 0L;
    }

    public boolean isOperationInFlight() {
        return mInFlightOperation != OP_NONE;
    }

    private void beginOperation(int operation) {
        mInFlightOperation = operation;
        mInFlightScope = mScopeGeneration;
    }

    private boolean acceptCompletion(long scopeGeneration, int operation) {
        if (scopeGeneration != mScopeGeneration
                || scopeGeneration != mInFlightScope
                || operation != mInFlightOperation) {
            return false;
        }
        clearInFlight();
        return true;
    }

    private void clearInFlight() {
        mInFlightOperation = OP_NONE;
        mInFlightScope = INVALID_SCOPE;
    }

    private void project(UndoStateParcel state) {
        if (state != null && Objects.equals(mPackageName, state.packageName)) {
            mProjection = state;
        } else if (state != null) {
            mProjection = null;
        }
        dispatchAvailability(false);
    }

    private void dispatchAvailability(boolean force) {
        boolean available = isUndoAvailable();
        if (!force && available == mLastAvailability) return;
        mLastAvailability = available;
        if (mListener != null) mListener.onAvailabilityChanged(available);
    }

    private static boolean isTransientFailure(int status) {
        return status == RuleServiceContract.RESULT_BUSY
                || status == RuleServiceContract.RESULT_WRITE_FAILED
                || status == RuleServiceContract.RESULT_UNCERTAIN;
    }

    public static final class UndoAttempt {
        public final long scopeGeneration;
        public final UndoStateParcel expected;

        private UndoAttempt(long scopeGeneration, UndoStateParcel expected) {
            this.scopeGeneration = scopeGeneration;
            this.expected = expected;
        }
    }
}
