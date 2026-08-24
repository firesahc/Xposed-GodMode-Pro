package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.kaisar.xposed.godmode.ipc.contract.RuleMutationRequest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class RuleMutationRequestInstrumentedTest {

    @Test
    public void parcelRoundTripSupportsEmptySingleAndDoubleFd() throws Exception {
        assertRoundTrip(null, null, false, 0);

        ParcelFileDescriptor[] main = ParcelFileDescriptor.createPipe();
        main[1].close();
        try {
            assertRoundTrip(main[0], null, false, Parcelable.CONTENTS_FILE_DESCRIPTOR);
        } finally {
            main[0].close();
        }

        main = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] modified = ParcelFileDescriptor.createPipe();
        main[1].close();
        modified[1].close();
        try {
            assertRoundTrip(main[0], modified[0], true,
                    Parcelable.CONTENTS_FILE_DESCRIPTOR);
        } finally {
            main[0].close();
            modified[0].close();
        }
    }

    private static void assertRoundTrip(ParcelFileDescriptor main,
                                        ParcelFileDescriptor modified,
                                        boolean captureUndo,
                                        int expectedContents) throws Exception {
        RuleMutationRequest source = new RuleMutationRequest(
                RuleServiceContract.MUTATION_WRITE, "request-1", "lease-1",
                "com.example.target", "{}", main, modified, null, captureUndo);
        assertEquals(expectedContents, source.describeContents());

        Parcel parcel = Parcel.obtain();
        RuleMutationRequest restored = null;
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            restored = RuleMutationRequest.CREATOR.createFromParcel(parcel);
            assertEquals(source.operation, restored.operation);
            assertEquals(source.requestId, restored.requestId);
            assertEquals(source.leaseToken, restored.leaseToken);
            assertEquals(source.packageName, restored.packageName);
            assertEquals(source.ruleJson, restored.ruleJson);
            assertEquals(captureUndo, restored.captureUndo);
            if (main == null) assertNull(restored.mainImageFd);
            else assertNotNull(restored.mainImageFd);
            if (modified == null) assertNull(restored.modifiedImageFd);
            else assertNotNull(restored.modifiedImageFd);
            assertEquals(expectedContents, restored.describeContents());
        } finally {
            if (restored != null) {
                if (restored.mainImageFd != null) restored.mainImageFd.close();
                if (restored.modifiedImageFd != null) restored.modifiedImageFd.close();
            }
            parcel.recycle();
        }
    }
}
