package com.kaisar.xposed.godmode.engine.applier;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Tracks one asynchronous image request until delivery or a terminal failure. */
final class ImageLoadState {

    private boolean pending;
    private long generation;
    private long bindingEpoch;

    synchronized long begin() {
        return begin(0L);
    }

    synchronized long begin(long expectedBindingEpoch) {
        if (pending) return -1L;
        pending = true;
        bindingEpoch = expectedBindingEpoch;
        return ++generation;
    }

    synchronized void cancel() {
        generation++;
        pending = false;
        bindingEpoch = 0L;
    }

    synchronized boolean isPending() {
        return pending;
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return pending && generation == expectedGeneration;
    }

    synchronized boolean isCurrent(long expectedGeneration, long expectedBindingEpoch) {
        return pending && generation == expectedGeneration
                && bindingEpoch == expectedBindingEpoch;
    }

    synchronized void finish(long expectedGeneration) {
        if (generation == expectedGeneration) pending = false;
    }

    synchronized void finish(long expectedGeneration, long expectedBindingEpoch) {
        if (generation == expectedGeneration && bindingEpoch == expectedBindingEpoch) {
            pending = false;
            bindingEpoch = 0L;
        }
    }

    <T> boolean submit(long expectedBindingEpoch, Executor executor, Callable<T> loader,
            Function<Runnable, Boolean> dispatcher, Consumer<T> resultHandler,
            Consumer<Throwable> failureHandler) {
        long requestGeneration = begin(expectedBindingEpoch);
        if (requestGeneration < 0L) return false;
        try {
            executor.execute(() -> run(requestGeneration, expectedBindingEpoch, loader, dispatcher,
                    resultHandler, failureHandler));
            return true;
        } catch (Throwable failure) {
            finish(requestGeneration, expectedBindingEpoch);
            reportFailure(failureHandler, failure);
            return false;
        }
    }

    <T> boolean submit(Executor executor, Callable<T> loader,
            Function<Runnable, Boolean> dispatcher, Consumer<T> resultHandler,
            Consumer<Throwable> failureHandler) {
        return submit(0L, executor, loader, dispatcher, resultHandler, failureHandler);
    }

    private <T> void run(long requestGeneration, long expectedBindingEpoch,
            Callable<T> loader,
            Function<Runnable, Boolean> dispatcher, Consumer<T> resultHandler,
            Consumer<Throwable> failureHandler) {
        try {
            T result = loader.call();
            if (!isCurrent(requestGeneration, expectedBindingEpoch)) return;
            boolean posted = Boolean.TRUE.equals(dispatcher.apply(() -> {
                try {
                    if (isCurrent(requestGeneration, expectedBindingEpoch)) {
                        resultHandler.accept(result);
                    }
                } catch (Throwable failure) {
                    reportFailure(failureHandler, failure);
                } finally {
                    finish(requestGeneration, expectedBindingEpoch);
                }
            }));
            if (!posted) finish(requestGeneration, expectedBindingEpoch);
        } catch (Throwable failure) {
            finish(requestGeneration, expectedBindingEpoch);
            reportFailure(failureHandler, failure);
        }
    }

    private static void reportFailure(Consumer<Throwable> handler,
            Throwable failure) {
        if (handler == null) return;
        try {
            handler.accept(failure);
        } catch (Throwable ignored) {
            // Failure reporting must not keep a request pending.
        }
    }
}
