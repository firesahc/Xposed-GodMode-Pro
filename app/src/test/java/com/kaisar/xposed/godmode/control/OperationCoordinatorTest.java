package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.ipc.RuleServiceContract;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class OperationCoordinatorTest {
    private static final String PACKAGE_A = "com.example.a";
    private static final String PACKAGE_B = "com.example.b";

    @Test
    public void targetMutationRequiresGlobalEditAndOwnPackage() {
        OperationCoordinator coordinator = new OperationCoordinator();
        Object editOwner = new Object();
        Object targetOwner = new Object();

        assertEquals(RuleServiceContract.RESULT_REJECTED,
                coordinator.open(RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                        false, true, targetOwner).status);

        OperationCoordinator.OpenResult edit = coordinator.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, editOwner);
        assertEquals(RuleServiceContract.RESULT_COMMITTED, edit.status);
        assertTrue(edit.editChanged);

        assertEquals(RuleServiceContract.RESULT_REJECTED,
                coordinator.open(RuleServiceContract.OP_MUTATION, PACKAGE_B, 20001,
                        false, false, targetOwner).status);
        assertEquals(RuleServiceContract.RESULT_COMMITTED,
                coordinator.open(RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                        false, true, targetOwner).status);
    }

    @Test
    public void globalEditAllowsDifferentOwnedPackagesToMutateConcurrently() {
        OperationCoordinator coordinator = new OperationCoordinator();
        Object editOwner = new Object();
        Object ownerA = new Object();
        Object ownerB = new Object();

        assertEquals(RuleServiceContract.RESULT_COMMITTED,
                coordinator.open(RuleServiceContract.OP_EDIT, null, 10001,
                        true, false, editOwner).status);
        OperationCoordinator.OpenResult mutationA = coordinator.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                false, true, ownerA);
        OperationCoordinator.OpenResult mutationB = coordinator.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_B, 20002,
                false, true, ownerB);

        assertEquals(RuleServiceContract.RESULT_COMMITTED, mutationA.status);
        assertEquals(RuleServiceContract.RESULT_COMMITTED, mutationB.status);
        assertNotNull(coordinator.beginPersistence(
                mutationA.token, ownerA, 20001, PACKAGE_A));
        assertNotNull(coordinator.beginPersistence(
                mutationB.token, ownerB, 20002, PACKAGE_B));
    }

    @Test
    public void managerMutationIsAllowedWithoutEditButMaintenanceIsExclusive()
            throws Exception {
        OperationCoordinator coordinator = new OperationCoordinator();
        Object mutationOwner = new Object();
        OperationCoordinator.OpenResult mutation = coordinator.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                true, false, mutationOwner);
        assertEquals(RuleServiceContract.RESULT_COMMITTED, mutation.status);
        assertEquals(RuleServiceContract.RESULT_BUSY,
                coordinator.open(RuleServiceContract.OP_BACKUP, null, 10001,
                        true, false, new Object()).status);

        assertTrue(coordinator.close(mutation.token, mutationOwner, 10001, 0L).closed);
        Object backupOwner = new Object();
        OperationCoordinator.OpenResult backup = coordinator.open(
                RuleServiceContract.OP_BACKUP, null, 10001,
                true, false, backupOwner);
        assertEquals(RuleServiceContract.RESULT_COMMITTED, backup.status);
        assertEquals(OperationCoordinator.State.MAINTENANCE, coordinator.state());
        assertEquals(RuleServiceContract.RESULT_BUSY,
                coordinator.open(RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                        true, true, new Object()).status);
    }

    @Test
    public void closingRejectsNewMutationsAndFinishesAfterAcceptedMutation()
            throws Exception {
        OperationCoordinator coordinator = new OperationCoordinator();
        Object editOwner = new Object();
        Object targetOwner = new Object();
        OperationCoordinator.OpenResult edit = coordinator.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, editOwner);
        OperationCoordinator.OpenResult mutation = coordinator.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                false, true, targetOwner);

        OperationCoordinator.CloseResult timedOut = coordinator.close(edit.token, editOwner,
                10001, 0L);
        assertFalse(timedOut.closed);
        assertEquals(OperationCoordinator.State.CLOSING, coordinator.state());
        assertEquals(RuleServiceContract.RESULT_BUSY,
                coordinator.open(RuleServiceContract.OP_MUTATION, PACKAGE_B, 20002,
                        false, true, new Object()).status);

        assertNotNull(coordinator.beginPersistence(mutation.token, targetOwner, 20001,
                PACKAGE_A));
        OperationCoordinator.CloseResult finished = coordinator.finishPersistence(
                mutation.token, targetOwner);
        assertTrue(finished.closed);
        assertTrue(finished.editChanged);
        assertFalse(finished.editEnabled);
        assertEquals(OperationCoordinator.State.IDLE, coordinator.state());
        assertEquals(2L, coordinator.editState().revision);
    }

    @Test
    public void managerDeathClosesEditAfterInFlightMutation() throws Exception {
        OperationCoordinator coordinator = new OperationCoordinator();
        Object editOwner = new Object();
        Object targetOwner = new Object();
        OperationCoordinator.OpenResult edit = coordinator.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, editOwner);
        OperationCoordinator.OpenResult mutation = coordinator.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                false, true, targetOwner);
        assertNotNull(coordinator.beginPersistence(mutation.token, targetOwner, 20001,
                PACKAGE_A));

        OperationCoordinator.CloseResult death = coordinator.ownerDied(edit.token, editOwner);
        assertFalse(death.closed);
        assertEquals(OperationCoordinator.State.CLOSING, coordinator.state());
        OperationCoordinator.CloseResult persisted = coordinator.finishPersistence(
                mutation.token, targetOwner);
        assertTrue(persisted.editChanged);
        assertEquals(OperationCoordinator.State.IDLE, coordinator.state());
    }

    @Test
    public void waitingCloseDoesNotFinalizeEditTwice() throws Exception {
        OperationCoordinator coordinator = new OperationCoordinator();
        Object editOwner = new Object();
        Object targetOwner = new Object();
        OperationCoordinator.OpenResult edit = coordinator.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, editOwner);
        OperationCoordinator.OpenResult mutation = coordinator.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                false, true, targetOwner);
        AtomicReference<OperationCoordinator.CloseResult> editClose = new AtomicReference<>();
        Thread closeThread = new Thread(() -> {
            try {
                editClose.set(coordinator.close(edit.token, editOwner, 10001, 2_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        closeThread.start();
        long deadline = System.currentTimeMillis() + 1_000L;
        while (coordinator.state() != OperationCoordinator.State.CLOSING
                && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }

        OperationCoordinator.CloseResult mutationClose = coordinator.close(mutation.token,
                targetOwner, 20001, 0L);
        closeThread.join(2_000L);

        assertTrue(mutationClose.editChanged);
        assertEquals(edit.token, mutationClose.releasedEditToken);
        assertNotNull(editClose.get());
        assertTrue(editClose.get().closed);
        assertFalse(editClose.get().editChanged);
        assertEquals(2L, coordinator.editState().revision);
    }

    @Test
    public void persistenceAccessIsBoundToOwnerUidAndPackage() {
        OperationCoordinator coordinator = new OperationCoordinator();
        Object owner = new Object();
        OperationCoordinator.OpenResult mutation = coordinator.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                true, false, owner);

        assertNull(coordinator.beginPersistence(mutation.token, owner, 10002, PACKAGE_A));
        assertNull(coordinator.beginPersistence(mutation.token, new Object(), 10001, PACKAGE_A));
        assertNull(coordinator.beginPersistence(mutation.token, owner, 10001, PACKAGE_B));
        assertNotNull(coordinator.beginPersistence(mutation.token, owner, 10001, PACKAGE_A));
        assertNull(coordinator.beginPersistence(mutation.token, owner, 10001, PACKAGE_A));
    }

    @Test
    public void onlyManagerMayUseGlobalMutationScope() {
        OperationCoordinator coordinator = new OperationCoordinator();
        assertEquals(RuleServiceContract.RESULT_REJECTED,
                coordinator.open(RuleServiceContract.OP_MUTATION,
                        RuleServiceContract.GLOBAL_SCOPE, 20001,
                        false, false, new Object()).status);
        assertEquals(RuleServiceContract.RESULT_COMMITTED,
                coordinator.open(RuleServiceContract.OP_MUTATION,
                        RuleServiceContract.GLOBAL_SCOPE, 10001,
                        true, false, new Object()).status);
    }
}
