package com.kaisar.xposed.godmode.ipc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClientEventOrderTest {

    @Test
    public void rejectsOldConnectionEpoch() {
        assertFalse(ClientEventOrder.isCurrent(4L, 5L, 10L, 10L));
        assertFalse(ClientEventOrder.isCurrent(6L, 5L, 10L, 10L));
    }

    @Test
    public void rejectsOldRevisionOrGenerationOnCurrentConnection() {
        assertFalse(ClientEventOrder.isCurrent(5L, 5L, 9L, 10L));
        assertTrue(ClientEventOrder.isCurrent(5L, 5L, 10L, 10L));
        assertTrue(ClientEventOrder.isCurrent(5L, 5L, 11L, 10L));
    }
}
