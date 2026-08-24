package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.engine.util.Logger;
import com.kaisar.xposed.godmode.rule.ActRules;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

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

    @Test
    public void applyCandidateCapturesActualBeforeAndAliasNormalizedAfter() {
        RuleRepository.RuleCache cache = new RuleRepository.RuleCache(
                new Gson(), Logger.getLogger("RuleRepositoryGenerationTest"));
        RuleRecord before = rule(new int[] {1}, "old");
        RuleRepository.RuleCache.CacheResult created = cache.prepareApply(
                "com.example", before, true);
        cache.commitApply("com.example", created);

        RuleRecord incoming = rule(new int[] {1}, null);
        incoming.label = "changed";
        RuleRepository.RuleCache.CacheResult updated = cache.prepareApply(
                "com.example", incoming, true);

        assertEquals("old", updated.beforeRule.alias);
        assertEquals("old", updated.appliedRule.alias);
        assertEquals("changed", updated.appliedRule.label);
    }

    @Test
    public void legacyDuplicateSlotStillTargetsLastWriterForUndoPlanning() {
        RuleRepository.RuleCache cache = new RuleRepository.RuleCache(
                new Gson(), Logger.getLogger("RuleRepositoryGenerationTest"));
        RuleRecord first = rule(new int[] {1}, "first");
        RuleRecord last = rule(new int[] {1}, "last");
        ActRules loaded = new ActRules();
        loaded.put("Activity", new ArrayList<>(Arrays.asList(first, last)));
        java.util.Map<String, ActRules> all = new java.util.HashMap<>();
        all.put("com.example", loaded);
        cache.putAll(all);

        RuleRecord current = cache.findRule("com.example", first);
        RuleRepository.RuleCache.DeleteResult deletion = cache.prepareDelete(
                "com.example", first);

        assertEquals("last", current.alias);
        assertEquals("last", deletion.removedRule.alias);
        assertEquals(1, deletion.snapshotRules.get("Activity").size());
        assertEquals("first", deletion.snapshotRules.get("Activity").get(0).alias);
    }

    private static RuleRecord rule(int[] depth, String alias) {
        return new RuleRecord("label", "com.example", "1", 1, 1,
                "", alias, 0, 0, 10, 10, depth, "Activity",
                "TextView", "id/title", "text", "", 0, 1L);
    }
}
