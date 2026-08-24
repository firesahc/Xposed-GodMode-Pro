package com.kaisar.xposed.godmode.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class EditorHistoryOwnerRegistryTest {
    private static final String PACKAGE_A = "com.example.a";
    private static final String PACKAGE_B = "com.example.b";

    @Test
    public void ownerIdentityIsStableAcrossPackagesAndRevisions() {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();

        EditorHistoryOwnerRegistry.OwnerLease first = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        EditorHistoryOwnerRegistry.OwnerLease second = registry.acquireOrCreate(
                owner, 1001, PACKAGE_B, 2L);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.scope().ownerId, second.scope().ownerId);
        assertNull(registry.acquireOrCreate(owner, 1002, PACKAGE_A, 2L));
        first.close();
        second.close();
    }

    @Test
    public void existingAcquireRequiresRecordedPackageAndRevision() {
        EditorHistoryOwnerRegistry registry = registry();
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease created = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);

        assertNull(registry.acquireExisting(owner, 1001, PACKAGE_B, 1L));
        assertNull(registry.acquireExisting(owner, 1001, PACKAGE_A, 2L));
        EditorHistoryOwnerRegistry.OwnerLease existing = registry.acquireExisting(
                owner, 1001, PACKAGE_A, 1L);
        assertNotNull(existing);
        existing.close();
        created.close();
    }

    @Test
    public void closeRevisionDefersEachPackageReleaseUntilEpochDrains() {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease first = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        EditorHistoryOwnerRegistry.OwnerLease second = registry.acquireOrCreate(
                owner, 1001, PACKAGE_B, 1L);

        registry.closeRevision(1L);
        assertFalse(first.isActive());
        assertFalse(second.isActive());
        assertNull(registry.acquireOrCreate(owner, 1001, PACKAGE_A, 1L));
        first.close();
        assertTrue(sink.scopes.isEmpty());
        second.close();

        assertEquals(2, sink.scopes.size());
        assertTrue(sink.hasScope(PACKAGE_A, 1L));
        assertTrue(sink.hasScope(PACKAGE_B, 1L));
    }

    @Test
    public void closingOldRevisionDoesNotAffectNewRevision() {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease oldLease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        EditorHistoryOwnerRegistry.OwnerLease newLease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 2L);

        registry.closeRevision(1L);
        oldLease.close();

        assertTrue(newLease.isActive());
        EditorHistoryOwnerRegistry.OwnerLease additional = registry.acquireExisting(
                owner, 1001, PACKAGE_A, 2L);
        assertNotNull(additional);
        additional.close();
        assertEquals(1, sink.scopes.size());
        assertEquals(1L, sink.scopes.get(0).editRevision);
        newLease.close();
    }

    @Test
    public void ownerDeathRejectsAcquireAndReleasesOwnerAfterLastLease() {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease lease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        String ownerId = lease.scope().ownerId;

        monitor.fireLatest(owner);

        assertFalse(lease.isActive());
        assertNull(registry.acquireExisting(owner, 1001, PACKAGE_A, 1L));
        assertTrue(sink.owners.isEmpty());
        lease.close();
        lease.close();

        assertEquals(Collections.singletonList(ownerId + ":1001"), sink.owners);
        assertEquals(1, monitor.unlinkCount);
    }

    @Test
    public void concurrentDeathAndRevisionClosePreferOneOwnerRelease() throws Exception {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease lease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        Thread death = new Thread(() -> {
            await(start);
            monitor.fireLatest(owner);
            done.countDown();
        });
        Thread close = new Thread(() -> {
            await(start);
            registry.closeRevision(1L);
            done.countDown();
        });
        death.start();
        close.start();
        start.countDown();
        assertTrue(done.await(2, TimeUnit.SECONDS));

        lease.close();

        assertEquals(1, sink.owners.size());
        assertTrue(sink.scopes.isEmpty());
    }

    @Test
    public void staleDeathCallbackCannotRevokeNewOwnerRecord() {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease oldLease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        Runnable staleCallback = monitor.latestCallback(owner);
        String oldOwnerId = oldLease.scope().ownerId;
        monitor.fireLatest(owner);
        oldLease.close();

        EditorHistoryOwnerRegistry.OwnerLease newLease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 2L);
        staleCallback.run();

        assertTrue(newLease.isActive());
        assertNotEquals(oldOwnerId, newLease.scope().ownerId);
        assertEquals(1, sink.owners.size());
        newLease.close();
    }

    @Test
    public void synchronousDeathDuringLinkNeverPublishesOwner() {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        monitor.dieDuringLink = true;
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);

        assertNull(registry.acquireOrCreate(new Object(), 1001, PACKAGE_A, 1L));
        assertEquals(1, monitor.unlinkCount);
        assertTrue(sink.owners.isEmpty());
    }

    @Test
    public void alreadyDeadOwnerDoesNotPublishOrRelease() {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        monitor.failLink = true;
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);

        assertNull(registry.acquireOrCreate(new Object(), 1001, PACKAGE_A, 1L));
        assertEquals(0, monitor.unlinkCount);
        assertTrue(sink.owners.isEmpty());
    }

    @Test
    public void concurrentFirstAcquireConvergesOnOneOwnerIdentity() throws Exception {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        monitor.blockFirstLink = true;
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();
        AtomicReference<EditorHistoryOwnerRegistry.OwnerLease> first = new AtomicReference<>();
        Thread firstThread = new Thread(() -> first.set(registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L)));
        firstThread.start();
        assertTrue(monitor.firstLinkEntered.await(2, TimeUnit.SECONDS));

        EditorHistoryOwnerRegistry.OwnerLease second = registry.acquireOrCreate(
                owner, 1001, PACKAGE_B, 1L);
        monitor.allowFirstLink.countDown();
        firstThread.join(2_000L);

        assertNotNull(first.get());
        assertNotNull(second);
        assertEquals(first.get().scope().ownerId, second.scope().ownerId);
        assertEquals(1, monitor.unlinkCount);
        first.get().close();
        second.close();
    }

    @Test
    public void shutdownRejectsAcquireAndWaitsForGuardAndCleanup() throws Exception {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        RecordingSink sink = new RecordingSink();
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease lease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        CountDownLatch shutdownDone = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            registry.shutdownAndDrain();
            shutdownDone.countDown();
        });
        shutdown.start();
        assertTrue(monitor.unlinkCalled.await(2, TimeUnit.SECONDS));

        assertNull(registry.acquireOrCreate(new Object(), 1002, PACKAGE_B, 2L));
        assertFalse(shutdownDone.await(100, TimeUnit.MILLISECONDS));
        lease.close();

        assertTrue(shutdownDone.await(2, TimeUnit.SECONDS));
        assertEquals(1, sink.owners.size());
    }

    @Test
    public void releaseSinkAndUnlinkAreCalledOutsideRegistryMutex() throws Exception {
        FakeDeathMonitor monitor = new FakeDeathMonitor();
        AtomicReference<EditorHistoryOwnerRegistry> registryRef = new AtomicReference<>();
        AtomicBoolean sinkReentered = new AtomicBoolean();
        AtomicBoolean linkReentered = new AtomicBoolean();
        AtomicBoolean unlinkReentered = new AtomicBoolean();
        EditorHistoryOwnerRegistry.ReleaseSink sink = new EditorHistoryOwnerRegistry.ReleaseSink() {
            @Override public void releaseScope(RuleRepository.UndoScope scope) {
                sinkReentered.set(canEnterFromAnotherThread(() -> {
                    EditorHistoryOwnerRegistry.OwnerLease nested =
                            registryRef.get().acquireOrCreate(
                                    new Object(), 2002, PACKAGE_B, 2L);
                    if (nested != null) nested.close();
                }));
            }

            @Override public void releaseOwner(String ownerId, int callingUid) { }
        };
        EditorHistoryOwnerRegistry registry = new EditorHistoryOwnerRegistry(monitor, sink);
        registryRef.set(registry);
        monitor.linkHook = () -> linkReentered.set(canEnterFromAnotherThread(() ->
                registry.acquireExisting(new Object(), 2004, PACKAGE_B, 3L)));
        monitor.unlinkHook = () -> unlinkReentered.set(canEnterFromAnotherThread(() ->
                registry.acquireExisting(new Object(), 2003, PACKAGE_B, 3L)));
        Object owner = new Object();
        EditorHistoryOwnerRegistry.OwnerLease lease = registry.acquireOrCreate(
                owner, 1001, PACKAGE_A, 1L);
        assertTrue(linkReentered.get());
        registry.closeRevision(1L);
        lease.close();
        assertTrue(sinkReentered.get());

        CountDownLatch shutdownDone = new CountDownLatch(1);
        new Thread(() -> {
            registry.shutdownAndDrain();
            shutdownDone.countDown();
        }).start();
        assertTrue(shutdownDone.await(2, TimeUnit.SECONDS));
        assertTrue(unlinkReentered.get());
    }

    private static boolean canEnterFromAnotherThread(Runnable operation) {
        CountDownLatch completed = new CountDownLatch(1);
        new Thread(() -> {
            operation.run();
            completed.countDown();
        }).start();
        try {
            return completed.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static EditorHistoryOwnerRegistry registry() {
        return new EditorHistoryOwnerRegistry(new FakeDeathMonitor(), new RecordingSink());
    }

    private static final class RecordingSink implements EditorHistoryOwnerRegistry.ReleaseSink {
        final List<RuleRepository.UndoScope> scopes = new CopyOnWriteArrayList<>();
        final List<String> owners = new CopyOnWriteArrayList<>();

        @Override public void releaseScope(RuleRepository.UndoScope scope) {
            scopes.add(scope);
        }

        @Override public void releaseOwner(String ownerId, int callingUid) {
            owners.add(ownerId + ":" + callingUid);
        }

        boolean hasScope(String packageName, long revision) {
            for (RuleRepository.UndoScope scope : scopes) {
                if (packageName.equals(scope.packageName) && revision == scope.editRevision) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class FakeDeathMonitor implements OwnerDeathMonitor {
        final Map<Object, List<Runnable>> callbacks =
                Collections.synchronizedMap(new IdentityHashMap<>());
        final CountDownLatch firstLinkEntered = new CountDownLatch(1);
        final CountDownLatch allowFirstLink = new CountDownLatch(1);
        final CountDownLatch unlinkCalled = new CountDownLatch(1);
        volatile boolean failLink;
        volatile boolean dieDuringLink;
        volatile boolean blockFirstLink;
        volatile Runnable linkHook;
        volatile Runnable unlinkHook;
        volatile int unlinkCount;
        private int links;

        @Override public Registration link(Object owner, Runnable callback)
                throws OwnerAlreadyDeadException {
            int linkNumber;
            synchronized (this) {
                linkNumber = ++links;
            }
            if (failLink) throw new OwnerAlreadyDeadException("dead", null);
            if (blockFirstLink && linkNumber == 1) {
                firstLinkEntered.countDown();
                await(allowFirstLink);
            }
            Runnable currentLinkHook = linkHook;
            if (currentLinkHook != null) currentLinkHook.run();
            callbacks.computeIfAbsent(owner, ignored -> new CopyOnWriteArrayList<>()).add(callback);
            AtomicBoolean linked = new AtomicBoolean(true);
            Registration registration = () -> {
                if (!linked.compareAndSet(true, false)) return;
                unlinkCount++;
                unlinkCalled.countDown();
                Runnable hook = unlinkHook;
                if (hook != null) hook.run();
            };
            if (dieDuringLink) callback.run();
            return registration;
        }

        Runnable latestCallback(Object owner) {
            List<Runnable> registered = callbacks.get(owner);
            return registered.get(registered.size() - 1);
        }

        void fireLatest(Object owner) {
            latestCallback(owner).run();
        }

    }
}
