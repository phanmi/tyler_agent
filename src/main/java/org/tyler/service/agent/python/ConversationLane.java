package org.tyler.service.agent.python;

import org.tyler.service.agent.protocol.ProtocolException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serializes chat and history changes with a bounded FIFO queue and monotonic queue deadlines. */
public final class ConversationLane implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final PythonAgentSettings settings;
    private volatile Thread owner;
    private final java.util.concurrent.ScheduledThreadPoolExecutor timer = new java.util.concurrent.ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("agent-queue-deadline").factory());

    public ConversationLane(PythonAgentSettings settings) {
        this.settings = settings;
        timer.setRemoveOnCancelPolicy(true);
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()), task -> {
                    Thread thread = new Thread(() -> { owner = Thread.currentThread(); task.run(); }, "agent-conversation");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Nested history access on the lane executes directly; callers never wait on themselves. */
    public <T> T call(Callable<T> action) {
        if (Thread.currentThread() == owner) return invoke(action);
        long submitted = System.nanoTime();
        LaneTask<T> task = new LaneTask<>(() -> {
            if (System.nanoTime() - submitted >= settings.queueTimeout().toNanos()) {
                throw new ProtocolException("DEADLINE_EXCEEDED");
            }
            return action.call();
        });
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            throw new ProtocolException(executor.isShutdown() ? "WORKER_UNAVAILABLE" : "WORKER_BUSY");
        }
        java.util.concurrent.ScheduledFuture<?> expiry;
        try {
            expiry = timer.schedule(() -> {
                if (executor.remove(task)) task.expire();
            }, settings.queueTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            task.cancel(true);
            executor.remove(task);
            throw new ProtocolException("WORKER_UNAVAILABLE");
        }
        try {
            return task.get();
        } catch (InterruptedException e) {
            task.cancel(true);
            executor.remove(task);
            Thread.currentThread().interrupt();
            throw new ProtocolException("REQUEST_CANCELLED");
        } catch (java.util.concurrent.CancellationException e) {
            throw new ProtocolException("WORKER_UNAVAILABLE");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new ProtocolException("INTERNAL_FAILURE");
        } finally {
            expiry.cancel(false);
        }
    }

    private <T> T invoke(Callable<T> action) {
        try { return action.call(); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new ProtocolException("INTERNAL_FAILURE"); }
    }

    int queuedRequests() { return executor.getQueue().size(); }

    @Override
    public void close() {
        executor.shutdownNow().forEach(task -> ((FutureTask<?>) task).cancel(false));
        timer.shutdownNow();
    }

    private static final class LaneTask<T> extends FutureTask<T> {
        LaneTask(Callable<T> action) { super(action); }
        void expire() { setException(new ProtocolException("DEADLINE_EXCEEDED")); }
    }
}
