package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClientEditStateTest {

    @Test
    public void closingLeaseBlocksReopenUntilAuthoritativeCloseArrives() {
        ClientEditState state = new ClientEditState();
        state.setLeaseToken("edit-1");
        assertTrue(state.accept(true, 1L));

        state.markClosing("edit-1");
        assertTrue(state.isClosing());
        assertFalse(state.canRequestEnable());

        assertTrue(state.accept(false, 2L));
        assertFalse(state.isClosing());
        assertTrue(state.canRequestEnable());
        assertNull(state.leaseToken());

        state.setLeaseToken("edit-2");
        assertTrue(state.accept(true, 3L));
        assertTrue(state.isEnabled());
    }

    @Test
    public void successfulCloseReplyStillWaitsForAuthoritativeDisabledRevision() {
        ClientEditState state = new ClientEditState();
        state.setLeaseToken("edit-1");
        assertTrue(state.accept(true, 1L));

        state.markClosing("edit-1");

        assertTrue(state.isClosing());
        assertFalse(state.canRequestEnable());
        assertTrue(state.accept(false, 2L));
        assertFalse(state.isClosing());
        assertNull(state.leaseToken());
    }

    @Test
    public void staleEditRevisionCannotUndoClosingState() {
        ClientEditState state = new ClientEditState();
        state.setLeaseToken("edit-1");
        assertTrue(state.accept(true, 5L));
        state.markClosing("edit-1");

        assertFalse(state.accept(false, 4L));
        assertTrue(state.isClosing());
        assertFalse(state.canRequestEnable());
    }

    @Test
    public void connectionResetClearsLeaseClosingAndRevision() {
        ClientEditState state = new ClientEditState();
        state.setLeaseToken("edit-1");
        assertTrue(state.accept(true, 7L));
        state.markClosing("edit-1");

        state.reset();

        assertNull(state.leaseToken());
        assertFalse(state.isKnown());
        assertFalse(state.isClosing());
        assertTrue(state.canRequestEnable());
        assertTrue(state.accept(false, 0L));
    }
}
