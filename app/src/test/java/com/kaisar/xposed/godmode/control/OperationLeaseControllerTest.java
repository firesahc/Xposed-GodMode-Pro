package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.kaisar.xposed.godmode.ipc.RuleServiceContract;

import org.junit.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class OperationLeaseControllerTest {
    private static final String PACKAGE_A = "com.example.a";

    @Test
    public void linkFailureRollsBackAcceptedEditWithClosedRevision() {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        deaths.failNext = true;
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);

        OperationCoordinator.OpenResult result = controller.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, new Object());

        assertEquals(RuleServiceContract.RESULT_BUSY, result.status);
        assertNull(result.token);
        assertEquals(1, listener.transitions.size());
        assertFalse(listener.transitions.get(0).enabled);
        assertEquals(1L, listener.transitions.get(0).closedEditRevision);
        assertEquals(2L, listener.transitions.get(0).revision);
    }

    @Test
    public void deathDuringLinkNeverPublishesEditEnabled() {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        deaths.dieDuringNextLink = true;
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);

        OperationCoordinator.OpenResult result = controller.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, new Object());

        assertEquals(RuleServiceContract.RESULT_BUSY, result.status);
        assertEquals(1, listener.transitions.size());
        assertFalse(listener.transitions.get(0).enabled);
        assertEquals(1L, listener.transitions.get(0).closedEditRevision);
    }

    @Test
    public void deathDuringOpenPublicationReturnsNoRevokedToken() {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        Object owner = new Object();
        RecordingListener listener = new RecordingListener() {
            @Override public void onEditTransition(OperationLeaseController.EditTransition result) {
                super.onEditTransition(result);
                if (result.enabled) deaths.die(owner);
            }
        };
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);

        OperationCoordinator.OpenResult result = controller.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, owner);

        assertEquals(RuleServiceContract.RESULT_BUSY, result.status);
        assertNull(result.token);
        assertEquals(2, listener.transitions.size());
        assertTrue(listener.transitions.get(0).enabled);
        assertFalse(listener.transitions.get(1).enabled);
        assertEquals(1L, listener.transitions.get(1).closedEditRevision);
        assertEquals(OperationCoordinator.State.IDLE, controller.editState().state);
    }

    @Test
    public void mutationPermitFinishIsIdempotentAndUnlinksLease() {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        Object owner = new Object();
        OperationCoordinator.OpenResult mutation = controller.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                true, false, owner);
        OperationLeaseController.PersistencePermit permit = controller.acquirePersistence(
                mutation.token, owner, 10001, PACKAGE_A);

        assertNotNull(permit);
        OperationCoordinator.CloseResult first = permit.finish();
        OperationCoordinator.CloseResult second = permit.finish();

        assertSame(first, second);
        assertTrue(first.closed);
        assertNull(controller.leaseInfo(mutation.token));
        assertEquals(1, deaths.unlinkCount);
    }

    @Test
    public void wrongOwnerAndUidCannotAcquireOrCloseLease() throws Exception {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        OperationLeaseController controller = new OperationLeaseController(
                deaths, new RecordingListener());
        Object owner = new Object();
        Object otherOwner = new Object();
        OperationCoordinator.OpenResult mutation = controller.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                true, false, owner);

        assertNull(controller.acquirePersistence(
                mutation.token, otherOwner, 10001, PACKAGE_A));
        assertNull(controller.acquirePersistence(
                mutation.token, owner, 10002, PACKAGE_A));
        assertFalse(controller.close(
                mutation.token, otherOwner, 10001, 0L).result.closed);
        assertFalse(controller.close(
                mutation.token, owner, 10002, 0L).result.closed);
        assertNotNull(controller.leaseInfo(mutation.token));

        assertTrue(controller.close(
                mutation.token, owner, 10001, 0L).result.closed);
        assertNull(controller.leaseInfo(mutation.token));
        assertEquals(1, deaths.unlinkCount);
    }

    @Test
    public void concurrentPermitCloseFinishesOnlyOnce() throws Exception {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        Object owner = new Object();
        OperationCoordinator.OpenResult mutation = controller.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                true, false, owner);
        OperationLeaseController.PersistencePermit permit = controller.acquirePersistence(
                mutation.token, owner, 10001, PACKAGE_A);
        AtomicReference<OperationCoordinator.CloseResult> first = new AtomicReference<>();
        AtomicReference<OperationCoordinator.CloseResult> second = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread one = new Thread(() -> {
            await(start);
            first.set(permit.finish());
        });
        Thread two = new Thread(() -> {
            await(start);
            second.set(permit.finish());
        });
        one.start();
        two.start();
        start.countDown();
        one.join(2_000L);
        two.join(2_000L);

        assertSame(first.get(), second.get());
        assertEquals(1, deaths.unlinkCount);
    }

    @Test
    public void restorePermitFinishKeepsLeaseUntilExplicitClose() throws Exception {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        Object owner = new Object();
        OperationCoordinator.OpenResult restore = controller.open(
                RuleServiceContract.OP_RESTORE, null, 10001, true, false, owner);
        OperationLeaseController.PersistencePermit permit = controller.acquirePersistence(
                restore.token, owner, 10001, PACKAGE_A);

        assertNotNull(permit);
        assertFalse(permit.finish().closed);
        assertNotNull(controller.leaseInfo(restore.token));

        OperationLeaseController.CloseOutcome closed = controller.close(
                restore.token, owner, 10001, 0L);
        assertTrue(closed.result.closed);
        assertNull(controller.leaseInfo(restore.token));
        assertEquals(1, deaths.unlinkCount);
    }

    @Test
    public void closeTimeoutIsCompletedByAcceptedMutationPermit() throws Exception {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        Object editOwner = new Object();
        Object targetOwner = new Object();
        OperationCoordinator.OpenResult edit = controller.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, editOwner);
        OperationCoordinator.OpenResult mutation = controller.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                false, true, targetOwner);
        OperationLeaseController.PersistencePermit permit = controller.acquirePersistence(
                mutation.token, targetOwner, 20001, PACKAGE_A);

        OperationLeaseController.CloseOutcome timedOut = controller.close(
                edit.token, editOwner, 10001, 0L);
        assertFalse(timedOut.result.closed);
        assertTrue(controller.editState().enabled);

        OperationCoordinator.CloseResult finished = permit.finish();
        assertTrue(finished.editChanged);
        assertEquals(1L, finished.closedEditRevision);
        assertFalse(controller.editState().enabled);
        assertNull(controller.leaseInfo(edit.token));
        assertNull(controller.leaseInfo(mutation.token));
        assertEquals(2, listener.transitions.size());
        assertTrue(listener.transitions.get(0).enabled);
        assertFalse(listener.transitions.get(1).enabled);
    }

    @Test
    public void ownerDeathDuringPersistenceWaitsForPermitFinish() throws Exception {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        Object editOwner = new Object();
        Object targetOwner = new Object();
        OperationCoordinator.OpenResult edit = controller.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, editOwner);
        OperationCoordinator.OpenResult mutation = controller.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                false, true, targetOwner);
        OperationLeaseController.PersistencePermit permit = controller.acquirePersistence(
                mutation.token, targetOwner, 20001, PACKAGE_A);

        deaths.die(targetOwner);
        assertNull(controller.leaseInfo(mutation.token));
        assertTrue(controller.editState().enabled);
        assertFalse(controller.close(edit.token, editOwner, 10001, 0L).result.closed);

        permit.close();
        assertFalse(controller.editState().enabled);
        assertEquals(1, listener.ownerDeaths.size());
        assertEquals(mutation.token, listener.ownerDeaths.get(0).token);
    }

    @Test
    public void closedRevisionIsRevokedBeforeDisabledCallbackCanOpenNewEdit() throws Exception {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        List<RuleRepository.UndoScope> releasedScopes = new ArrayList<>();
        EditorHistoryOwnerRegistry histories = new EditorHistoryOwnerRegistry(deaths,
                new EditorHistoryOwnerRegistry.ReleaseSink() {
                    @Override public void releaseScope(RuleRepository.UndoScope scope) {
                        releasedScopes.add(scope);
                    }

                    @Override public void releaseOwner(String ownerId, int callingUid) { }
                });
        Object editOwner = new Object();
        Object targetOwner = new Object();
        AtomicReference<OperationLeaseController> controllerRef = new AtomicReference<>();
        AtomicReference<EditorHistoryOwnerRegistry.OwnerLease> newHistory =
                new AtomicReference<>();
        OperationLeaseController.Listener listener = new RecordingListener() {
            @Override public void onEditRevisionClosed(long editRevision) {
                histories.closeRevision(editRevision);
            }

            @Override public void onEditTransition(
                    OperationLeaseController.EditTransition transition) {
                super.onEditTransition(transition);
                if (transition.enabled) return;
                assertNull(histories.acquireExisting(targetOwner, 20001, PACKAGE_A,
                        transition.closedEditRevision));
                OperationCoordinator.OpenResult reopened = controllerRef.get().open(
                        RuleServiceContract.OP_EDIT, null, 10001,
                        true, false, editOwner);
                assertEquals(RuleServiceContract.RESULT_COMMITTED, reopened.status);
                newHistory.set(histories.acquireOrCreate(targetOwner, 20001, PACKAGE_A,
                        reopened.editRevision));
            }

            @Override public void onOwnerDied(OperationLeaseController.LeaseInfo lease) { }
        };
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        controllerRef.set(controller);
        OperationCoordinator.OpenResult edit = controller.open(
                RuleServiceContract.OP_EDIT, null, 10001, true, false, editOwner);
        OperationCoordinator.OpenResult mutation = controller.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 20001,
                false, true, targetOwner);
        EditorHistoryOwnerRegistry.OwnerLease oldHistory = histories.acquireOrCreate(
                targetOwner, 20001, PACKAGE_A, edit.editRevision);
        assertNotNull(oldHistory);
        oldHistory.close();
        OperationLeaseController.PersistencePermit permit = controller.acquirePersistence(
                mutation.token, targetOwner, 20001, PACKAGE_A);
        assertNotNull(permit);

        assertFalse(controller.close(edit.token, editOwner, 10001, 0L).result.closed);
        permit.close();

        assertNotNull(newHistory.get());
        assertTrue(newHistory.get().isActive());
        assertEquals(1, releasedScopes.size());
        assertEquals(edit.editRevision, releasedScopes.get(0).editRevision);
        assertEquals(3L, newHistory.get().scope().editRevision);
        newHistory.get().close();
        controller.shutdownAndDrain();
        histories.shutdownAndDrain();
    }

    @Test
    public void listenerRunsWithoutControllerRegistryLock() {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        AtomicReference<OperationLeaseController> controllerRef = new AtomicReference<>();
        AtomicBoolean registryWasAvailable = new AtomicBoolean();
        RecordingListener listener = new RecordingListener() {
            @Override public void onEditTransition(OperationLeaseController.EditTransition result) {
                super.onEditTransition(result);
                Thread probe = new Thread(() -> {
                    controllerRef.get().shutdownAndDrain();
                    registryWasAvailable.set(true);
                });
                probe.start();
                try {
                    probe.join(1_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        controllerRef.set(controller);

        OperationCoordinator.OpenResult opened = controller.open(
                RuleServiceContract.OP_EDIT, null, 10001,
                true, false, new Object());

        assertTrue(registryWasAvailable.get());
        assertEquals(RuleServiceContract.RESULT_BUSY, opened.status);
        assertTrue(listener.ownerDeaths.isEmpty());
    }

    @Test
    public void shutdownRejectsNewWorkAndWaitsForActivePermit() throws Exception {
        FakeDeathMonitor deaths = new FakeDeathMonitor();
        RecordingListener listener = new RecordingListener();
        OperationLeaseController controller = new OperationLeaseController(deaths, listener);
        Object owner = new Object();
        OperationCoordinator.OpenResult mutation = controller.open(
                RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                true, false, owner);
        OperationLeaseController.PersistencePermit permit = controller.acquirePersistence(
                mutation.token, owner, 10001, PACKAGE_A);

        CountDownLatch shutdownDone = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            controller.shutdownAndDrain();
            shutdownDone.countDown();
        });
        shutdown.start();
        assertFalse(shutdownDone.await(100L, TimeUnit.MILLISECONDS));
        assertEquals(RuleServiceContract.RESULT_BUSY,
                controller.open(RuleServiceContract.OP_MUTATION, PACKAGE_A, 10001,
                        true, false, new Object()).status);
        permit.close();
        assertTrue(shutdownDone.await(2L, TimeUnit.SECONDS));

        assertNull(controller.leaseInfo(mutation.token));
        assertEquals(1, deaths.unlinkCount);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2L, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static class RecordingListener implements OperationLeaseController.Listener {
        final List<OperationLeaseController.EditTransition> transitions = new ArrayList<>();
        final List<OperationLeaseController.LeaseInfo> ownerDeaths = new ArrayList<>();

        @Override public void onEditRevisionClosed(long editRevision) { }

        @Override public void onEditTransition(OperationLeaseController.EditTransition result) {
            transitions.add(result);
        }

        @Override public void onOwnerDied(OperationLeaseController.LeaseInfo lease) {
            ownerDeaths.add(lease);
        }
    }

    private static final class FakeDeathMonitor implements OwnerDeathMonitor {
        final Map<Object, FakeRegistration> registrations = new IdentityHashMap<>();
        boolean failNext;
        boolean dieDuringNextLink;
        int unlinkCount;

        @Override public Registration link(Object owner, Runnable callback)
                throws OwnerAlreadyDeadException {
            if (failNext) {
                failNext = false;
                throw new OwnerAlreadyDeadException("dead", null);
            }
            FakeRegistration registration = new FakeRegistration(owner, callback);
            registrations.put(owner, registration);
            if (dieDuringNextLink) {
                dieDuringNextLink = false;
                callback.run();
            }
            return registration;
        }

        void die(Object owner) {
            FakeRegistration registration = registrations.get(owner);
            if (registration != null && !registration.unlinked) registration.callback.run();
        }

        private final class FakeRegistration implements Registration {
            final Object owner;
            final Runnable callback;
            boolean unlinked;

            FakeRegistration(Object owner, Runnable callback) {
                this.owner = owner;
                this.callback = callback;
            }

            @Override public void unlink() {
                if (unlinked) return;
                unlinked = true;
                unlinkCount++;
                registrations.remove(owner);
            }
        }
    }
}
