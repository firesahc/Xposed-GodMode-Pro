package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuleRepositoryGenerationTest {

    @Test
    public void deleteTombstoneRejectsTheDeleteGenerationAndOlderWrites() {
        assertFalse(RuleRepository.RuleStore.isWriteCurrent(41L, 41L));
        assertFalse(RuleRepository.RuleStore.isWriteCurrent(40L, 41L));
    }

    @Test
    public void writeAfterDeleteWithNewGenerationIsAccepted() {
        assertTrue(RuleRepository.RuleStore.isWriteCurrent(42L, 41L));
    }
}
