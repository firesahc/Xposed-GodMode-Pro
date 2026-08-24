package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.kaisar.xposed.godmode.ipc.contract.RuleMutationResult;
import com.kaisar.xposed.godmode.ipc.contract.UndoRequestParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoResultParcel;
import com.kaisar.xposed.godmode.ipc.contract.UndoStateParcel;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UndoContractParcelInstrumentedTest {

    @Test
    public void undoStateRoundTripsAuthoritativeProjection() {
        UndoStateParcel restored = roundTrip(new UndoStateParcel(
                RuleServiceContract.RESULT_COMMITTED, "com.example.target",
                7L, 11L, 3, 29L, "source-1", "ready"),
                UndoStateParcel.CREATOR);

        assertEquals(RuleServiceContract.RESULT_COMMITTED, restored.status);
        assertEquals("com.example.target", restored.packageName);
        assertEquals(7L, restored.editRevision);
        assertEquals(11L, restored.historyRevision);
        assertEquals(3, restored.depth);
        assertEquals(29L, restored.topSequence);
        assertEquals("source-1", restored.topSourceRequestId);
        assertEquals("ready", restored.message);
    }

    @Test
    public void undoRequestRoundTripsCasCoordinates() {
        UndoRequestParcel restored = roundTrip(new UndoRequestParcel(
                "undo-1", "lease-1", "com.example.target", 7L, 11L, 29L),
                UndoRequestParcel.CREATOR);

        assertEquals("undo-1", restored.requestId);
        assertEquals("lease-1", restored.leaseToken);
        assertEquals("com.example.target", restored.packageName);
        assertEquals(7L, restored.expectedEditRevision);
        assertEquals(11L, restored.expectedHistoryRevision);
        assertEquals(29L, restored.expectedTopSequence);
    }

    @Test
    public void mutationAndUndoResultsRoundTripUpdatedState() {
        UndoStateParcel state = new UndoStateParcel(RuleServiceContract.RESULT_COMMITTED,
                "com.example.target", 7L, 12L, 2, 23L, "source-0", null);
        RuleMutationResult mutation = roundTrip(new RuleMutationResult(
                RuleServiceContract.RESULT_COMMITTED, "source-1", "com.example.target",
                41L, null, state, null), RuleMutationResult.CREATOR);
        UndoResultParcel undo = roundTrip(new UndoResultParcel(
                RuleServiceContract.RESULT_COMMITTED, "undo-1", "com.example.target",
                42L, state, null), UndoResultParcel.CREATOR);

        assertNotNull(mutation.undoState);
        assertEquals(12L, mutation.undoState.historyRevision);
        assertNotNull(undo.undoState);
        assertEquals(42L, undo.generation);
        assertEquals(23L, undo.undoState.topSequence);
    }

    private static <T extends Parcelable> T roundTrip(T source, Parcelable.Creator<T> creator) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
