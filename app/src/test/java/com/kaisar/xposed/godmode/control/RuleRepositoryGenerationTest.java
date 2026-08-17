package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.RuleRecord;

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

    @Test
    public void candidateGenerationIsNotPublishedUntilCommit() {
        RuleRepository.RuleCache cache = new RuleRepository.RuleCache(
                new Gson(), Logger.getLogger("RuleRepositoryGenerationTest"));

        long proposed = cache.proposedGeneration();

        assertEquals(0L, cache.currentGeneration());
        cache.commitGeneration(proposed);
        assertEquals(proposed, cache.currentGeneration());
    }

    @Test
    public void copyForPackageOwnsRecordAndNormalizesScope() {
        RuleRecord input = new RuleRecord("label", "other.package", "1", 1, 1,
                "", "alias", 0, 0, 10, 10, new int[]{1}, "Activity",
                "TextView", "id/title", "text", "", 0, 1L);
        RuleRecord copy = RuleRepository.copyForPackage("com.example", input);

        assertTrue(copy != input);
        assertTrue("com.example".equals(copy.packageName));
        assertTrue("other.package".equals(input.packageName));
        int[] returnedDepth = copy.getDepth();
        returnedDepth[0] = 9;
        assertTrue(input.getDepth()[0] == 1);
    }

    @Test
    public void pendingSnapshotCancellationMatchesOnlySamePackageAndView() {
        RuleRecord pending = new RuleRecord("label", "com.example", "1", 1, 1,
                "", "alias", 0, 0, 10, 10, new int[]{1}, "Activity",
                "TextView", "id/title", "text", "", 0, 1L);
        RuleRecord same = pending.clone();
        RuleRecord differentView = pending.withMatchSpec(pending.getMatchSpec().toBuilder()
                .depth(new int[] {2}).build());

        assertTrue(RuleRepository.isPendingSnapshotFor("com.example", pending,
                "com.example", same));
        assertTrue(!RuleRepository.isPendingSnapshotFor("com.example", pending,
                "other.package", same));
        assertTrue(!RuleRepository.isPendingSnapshotFor("com.example", pending,
                "com.example", differentView));
    }
}
