package com.houzhengbo.interview.utils;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal future type used by {@link DbExecutor} to expose the result of a
 * background Callable to callers that want it without forcing every call
 * site to wrap results in a holder object.  Implementation is intentionally
 * package-private; callers should treat it as opaque.
 */
final class FutureTaskImpl<T> implements Future<T> {

    private T result;
    private Throwable error;
    private boolean done;
    private final Object lock = new Object();

    void complete(T result) {
        synchronized (lock) {
            this.result = result;
            this.done = true;
            lock.notifyAll();
        }
    }

    void completeError(Throwable t) {
        synchronized (lock) {
            this.error = t;
            this.done = true;
            lock.notifyAll();
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public boolean isDone() {
        synchronized (lock) {
            return done;
        }
    }

    @Override
    public T get() throws InterruptedException, ExecutionException {
        synchronized (lock) {
            while (!done) lock.wait();
        }
        if (error != null) throw new ExecutionException(error);
        return result;
    }

    @Override
    public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (lock) {
            while (!done) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
        if (error != null) throw new ExecutionException(error);
        return result;
    }
}
