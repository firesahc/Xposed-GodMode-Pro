package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.kaisar.xposed.godmode.rule.RuleRecord;

import org.junit.Test;

import java.util.Set;

public final class AuthoritativeUndoJournalTest {
    private static final String PACKAGE = "com.example";
    private final Gson gson = new Gson();

    @Test
    public void recordsRealCreateAndUpdateWithDeepCopies() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope scope = scope("owner", 1L);
        RuleRecord created = rule(1, "created", "created.webp");

        journal.recordForward(scope, "create", null, created,
                journal.fingerprint(created), 1L);
        AuthoritativeUndoJournal.Entry create = journal.peekLatest(scope);
        assertEquals(AuthoritativeUndoJournal.Operation.CREATE, create.operation);
        assertNull(create.before);

        RuleRecord before = created.clone();
        RuleRecord after = created.withAlias("updated");
        journal.recordForward(scope, "update", before, after,
                journal.fingerprint(after), 2L);
        before.alias = "mutated by caller";
        after.alias = "also mutated";

        AuthoritativeUndoJournal.Entry update = journal.peekLatest(scope);
        assertEquals(AuthoritativeUndoJournal.Operation.UPDATE, update.operation);
        assertEquals("created", update.before.alias);
        assertEquals("updated", update.after.alias);
        assertEquals(2, journal.state(scope).depth);
    }

    @Test
    public void fingerprintCoversFieldsOmittedByUiComparator() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        RuleRecord left = rule(1, "same", "preview.webp");
        RuleRecord changedLabel = left.clone();
        changedLabel.label = "different label";
        RuleRecord changedTimestamp = left.clone();
        changedTimestamp.timestamp++;

        assertNotEquals(journal.fingerprint(left), journal.fingerprint(changedLabel));
        assertNotEquals(journal.fingerprint(left), journal.fingerprint(changedTimestamp));
    }

    @Test
    public void consecutiveSameSlotUndoRebindsParentLineage() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope scope = scope("owner", 1L);
        RuleRecord first = rule(1, "first", null);
        RuleRecord second = first.withAlias("second");

        journal.recordForward(scope, "one", null, first,
                journal.fingerprint(first), 1L);
        journal.recordForward(scope, "two", first, second,
                journal.fingerprint(second), 2L);

        AuthoritativeUndoJournal.Entry secondEntry = journal.peekLatest(scope);
        assertTrue(journal.matchesCurrent(secondEntry, second));
        journal.commitUndo(scope, secondEntry);

        AuthoritativeUndoJournal.Entry firstEntry = journal.peekLatest(scope);
        assertNotNull(firstEntry);
        assertTrue(journal.matchesCurrent(firstEntry, first));
        journal.commitUndo(scope, firstEntry);
        assertEquals(0, journal.state(scope).depth);
    }

    @Test
    public void interleavedSlotsKeepIndependentParentLineages() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope scope = scope("owner", 1L);
        RuleRecord a1 = rule(1, "a1", null);
        RuleRecord b1 = rule(2, "b1", null);
        RuleRecord a2 = a1.withAlias("a2");
        journal.recordForward(scope, "a1", null, a1, journal.fingerprint(a1), 1L);
        journal.recordForward(scope, "b1", null, b1, journal.fingerprint(b1), 2L);
        journal.recordForward(scope, "a2", a1, a2, journal.fingerprint(a2), 3L);

        AuthoritativeUndoJournal.Entry a2Entry = journal.peekLatest(scope);
        journal.commitUndo(scope, a2Entry);
        AuthoritativeUndoJournal.Entry b1Entry = journal.peekLatest(scope);
        assertTrue(journal.matchesCurrent(b1Entry, b1));
        journal.commitUndo(scope, b1Entry);

        AuthoritativeUndoJournal.Entry a1Entry = journal.peekLatest(scope);
        assertTrue(journal.matchesCurrent(a1Entry, a1));
    }

    @Test
    public void externalMutationInvalidatesHistoryAndPreventsAba() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope scope = scope("owner", 1L);
        RuleRecord rule = rule(1, "value", null);

        journal.recordForward(scope, "one", null, rule,
                journal.fingerprint(rule), 1L);
        AuthoritativeUndoJournal.Entry oldEntry = journal.peekLatest(scope);
        journal.recordExternalSlotMutation(rule.slotKey(PACKAGE));

        assertEquals(0, journal.state(scope).depth);
        assertFalse(journal.matchesCurrent(oldEntry, rule));
    }

    @Test
    public void externalMutationInvalidatesOnlyTheTouchedSlotAcrossOwners() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope first = scope("first", 1L);
        AuthoritativeUndoJournal.Scope second = scope("second", 1L);
        RuleRecord touched = rule(1, "touched", null);
        RuleRecord untouched = rule(2, "untouched", null);
        journal.recordForward(first, "first", null, touched,
                journal.fingerprint(touched), 1L);
        journal.recordForward(second, "second", null, untouched,
                journal.fingerprint(untouched), 2L);

        journal.recordExternalSlotMutation(touched.slotKey(PACKAGE));

        assertEquals(0, journal.state(first).depth);
        assertEquals(1, journal.state(second).depth);
        assertTrue(journal.matchesCurrent(journal.peekLatest(second), untouched));
    }

    @Test
    public void staleTopDiscardsTheWholeSlotChain() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope scope = scope("owner", 1L);
        RuleRecord first = rule(1, "first", null);
        RuleRecord second = first.withAlias("second");
        journal.recordForward(scope, "first", null, first,
                journal.fingerprint(first), 1L);
        journal.recordForward(scope, "second", first, second,
                journal.fingerprint(second), 2L);

        journal.discardStaleTop(scope, journal.peekLatest(scope));

        assertEquals(0, journal.state(scope).depth);
    }

    @Test
    public void capacityEvictionAndScopeReleaseDropImageProtection() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson, 2);
        AuthoritativeUndoJournal.Scope scope = scope("owner", 1L);

        recordUpdate(journal, scope, "one", rule(1, "old1", "old1.webp"));
        recordUpdate(journal, scope, "two", rule(2, "old2", "old2.webp"));
        recordUpdate(journal, scope, "three", rule(3, "old3", "old3.webp"));

        Set<String> protectedPaths = journal.protectedPaths();
        assertEquals(2, journal.state(scope).depth);
        assertFalse(protectedPaths.contains("old1.webp"));
        assertTrue(protectedPaths.contains("old2.webp"));
        assertTrue(protectedPaths.contains("old3.webp"));
        assertTrue(journal.takeReleasedPaths().contains("old1.webp"));

        journal.releaseScope(scope);
        assertTrue(journal.protectedPaths().isEmpty());
        Set<String> released = journal.takeReleasedPaths();
        assertTrue(released.contains("old2.webp"));
        assertTrue(released.contains("old3.webp"));
    }

    @Test
    public void historiesAreIsolatedByOwnerUidPackageAndEditRevision() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope first = scope("owner", 1L);
        AuthoritativeUndoJournal.Scope otherEdit = scope("owner", 2L);
        RuleRecord firstSlot = rule(1, "one", null);
        RuleRecord secondSlot = rule(2, "two", null);

        journal.recordForward(first, "one", null, firstSlot,
                journal.fingerprint(firstSlot), 1L);
        journal.recordForward(otherEdit, "two", null, secondSlot,
                journal.fingerprint(secondSlot), 2L);
        journal.releaseScope(first);

        assertEquals(0, journal.state(first).depth);
        assertEquals(1, journal.state(otherEdit).depth);
        journal.releaseOwner("owner", 1000);
        assertEquals(0, journal.state(otherEdit).depth);
    }

    @Test
    public void requestReplayAndUndoCasAreStable() {
        AuthoritativeUndoJournal journal = new AuthoritativeUndoJournal(gson);
        AuthoritativeUndoJournal.Scope scope = scope("owner", 1L);
        RuleRecord rule = rule(1, "one", null);
        String sourceFingerprint = journal.fingerprint(rule);
        journal.recordForward(scope, "forward", null, rule, sourceFingerprint, 7L);
        AuthoritativeUndoJournal.State state = journal.state(scope);

        AuthoritativeUndoJournal.ForwardReplay forward = journal.findForwardReplay(
                scope, "forward", rule.slotKey(PACKAGE), sourceFingerprint);
        assertNotNull(forward);
        assertEquals(7L, forward.generation);
        assertTrue(journal.hasForwardRequest(scope, "forward"));
        assertNull(journal.findForwardReplay(scope, "forward", rule(2, "other", null)
                .slotKey(PACKAGE), sourceFingerprint));
        assertTrue(journal.matchesExpectedState(
                scope, state.historyRevision, state.topSequence));
        assertFalse(journal.matchesExpectedState(
                scope, state.historyRevision - 1L, state.topSequence));

        journal.recordUndoReplay(scope, "undo",
                AuthoritativeUndoJournal.UndoReplayStatus.CAS_MISMATCH, 0L);
        assertEquals(AuthoritativeUndoJournal.UndoReplayStatus.CAS_MISMATCH,
                journal.findUndoReplay(scope, "undo").status);
    }

    private static void recordUpdate(AuthoritativeUndoJournal journal,
                                     AuthoritativeUndoJournal.Scope scope,
                                     String requestId, RuleRecord before) {
        RuleRecord after = before.withAlias(before.alias + "-new");
        journal.recordForward(scope, requestId, before, after,
                journal.fingerprint(after), requestId.hashCode());
    }

    private static AuthoritativeUndoJournal.Scope scope(String owner, long editRevision) {
        return new AuthoritativeUndoJournal.Scope(owner, 1000, PACKAGE, editRevision);
    }

    private static RuleRecord rule(int depth, String alias, String imagePath) {
        return new RuleRecord("label", PACKAGE, "1", 1, 1, imagePath, alias,
                0, 0, 10, 10, new int[] {depth}, "Activity", "TextView",
                "com.example:id/title", "text", "description", 8, 1L);
    }
}
