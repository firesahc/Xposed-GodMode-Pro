package com.kaisar.xposed.godmode.engine.applier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ImageLoadStateTest {

    @Test
    public void pendingRequestSuppressesDuplicateUntilDelivery() {
        ImageLoadState state = new ImageLoadState();
        AtomicReference<Runnable> worker = new AtomicReference<>();
        AtomicBoolean delivered = new AtomicBoolean();

        assertTrue(state.submit(worker::set, () -> "image", action -> {
            action.run();
            return true;
        }, result -> delivered.set(true), failure -> { }));
        assertFalse(state.submit(worker::set, () -> "duplicate",
                action -> true, result -> { }, failure -> { }));
        assertTrue(state.isPending());

        worker.get().run();

        assertTrue(delivered.get());
        assertFalse(state.isPending());
    }

    @Test
    public void cancellationRejectsLateDelivery() {
        ImageLoadState state = new ImageLoadState();
        AtomicReference<Runnable> worker = new AtomicReference<>();
        AtomicBoolean delivered = new AtomicBoolean();
        assertTrue(state.submit(worker::set, () -> "old", action -> {
            action.run();
            return true;
        }, result -> delivered.set(true), failure -> { }));

        state.cancel();
        worker.get().run();

        assertFalse(delivered.get());
        assertFalse(state.isPending());
    }

    @Test
    public void executorRejectionEndsPendingAndAllowsRetry() {
        ImageLoadState state = new ImageLoadState();
        assertFalse(state.submit(action -> {
            throw new java.util.concurrent.RejectedExecutionException("rejected");
        }, () -> "image", action -> true, result -> { }, failure -> { }));
        assertFalse(state.isPending());
        assertTrue(state.begin() > 0L);
    }

    @Test
    public void loaderFailureEndsPendingAndAllowsRetry() {
        ImageLoadState state = new ImageLoadState();
        assertTrue(state.submit(Runnable::run, () -> {
            throw new IllegalStateException("decode failed");
        }, action -> true, result -> { }, failure -> { }));
        assertFalse(state.isPending());
        assertTrue(state.begin() > 0L);
    }

    @Test
    public void rejectedDispatchEndsPendingAndAllowsRetry() {
        ImageLoadState state = new ImageLoadState();
        assertTrue(state.submit(Runnable::run, () -> "image",
                action -> false, result -> { }, failure -> { }));
        assertFalse(state.isPending());
        assertTrue(state.begin() > 0L);
    }

    @Test
    public void resultHandlerFailureEndsPending() {
        ImageLoadState state = new ImageLoadState();
        assertTrue(state.submit(Runnable::run, () -> "image", action -> {
            action.run();
            return true;
        }, result -> {
            throw new IllegalStateException("delivery failed");
        }, failure -> { }));
        assertFalse(state.isPending());
    }
}
