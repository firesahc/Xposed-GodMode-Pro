package com.kaisar.xposed.godmode.engine.applier;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Tracks one asynchronous image request until delivery or a terminal failure. */
final class ImageLoadState {

    private boolean pending;
    private long generation;

    synchronized long begin() {
        if (pending) return -1L;
        pending = true;
        return ++generation;
    }

    synchronized void cancel() {
        generation++;
        pending = false;
    }

    synchronized boolean isPending() {
        return pending;
    }

    synchronized boolean isCurrent(long expectedGeneration) {
        return pending && generation == expectedGeneration;
    }

    synchronized void finish(long expectedGeneration) {
        if (generation == expectedGeneration) pending = false;
    }

    <T> boolean submit(Executor executor, Callable<T> loader,
            Function<Runnable, Boolean> dispatcher, Consumer<T> resultHandler,
            Consumer<Throwable> failureHandler) {
        long requestGeneration = begin();
        if (requestGeneration < 0L) return false;
        try {
            executor.execute(() -> run(requestGeneration, loader, dispatcher,
                    resultHandler, failureHandler));
            return true;
        } catch (Throwable failure) {
            finish(requestGeneration);
            reportFailure(failureHandler, failure);
            return false;
        }
    }

    private <T> void run(long requestGeneration, Callable<T> loader,
            Function<Runnable, Boolean> dispatcher, Consumer<T> resultHandler,
            Consumer<Throwable> failureHandler) {
        try {
            T result = loader.call();
            if (!isCurrent(requestGeneration)) return;
            boolean posted = Boolean.TRUE.equals(dispatcher.apply(() -> {
                try {
                    if (isCurrent(requestGeneration)) resultHandler.accept(result);
                } catch (Throwable failure) {
                    reportFailure(failureHandler, failure);
                } finally {
                    finish(requestGeneration);
                }
            }));
            if (!posted) finish(requestGeneration);
        } catch (Throwable failure) {
            finish(requestGeneration);
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
