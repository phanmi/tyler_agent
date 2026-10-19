package org.tyler.service.agent.python;

import org.tyler.service.agent.protocol.ProtocolException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serializes chat and history changes with a bounded FIFO queue and monotonic queue deadlines. */
public final class ConversationLane implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final PythonAgentSettings settings;
    private volatile Thread owner;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
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
        LaneTask<T> task = createTask(action);
        submit(task);
        ScheduledFuture<?> expiry = scheduleExpiry(task);
        try {
            return awaitResult(task);
        } finally {
            expiry.cancel(false);
        }
    }

    private <T> LaneTask<T> createTask(Callable<T> action) {
        long submitted = System.nanoTime();
        return new LaneTask<>(() -> {
            if (System.nanoTime() - submitted >= settings.queueTimeout().toNanos()) {
                throw new ProtocolException("DEADLINE_EXCEEDED");
            }
            return action.call();
        });
    }

    private void submit(LaneTask<?> task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            throw new ProtocolException(executor.isShutdown() ? "WORKER_UNAVAILABLE" : "WORKER_BUSY");
        }
    }

    private ScheduledFuture<?> scheduleExpiry(LaneTask<?> task) {
        try {
            return timer.schedule(() -> {
                if (executor.remove(task)) task.expire();
            }, settings.queueTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException e) {
            cancel(task);
            throw new ProtocolException("WORKER_UNAVAILABLE");
        }
    }

    private <T> T awaitResult(LaneTask<T> task) {
        try {
            return task.get();
        } catch (InterruptedException e) {
            cancel(task);
            Thread.currentThread().interrupt();
            throw new ProtocolException("REQUEST_CANCELLED");
        } catch (CancellationException e) {
            throw new ProtocolException("WORKER_UNAVAILABLE");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException failure) throw failure;
            throw new ProtocolException("INTERNAL_FAILURE");
        }
    }

    private void cancel(LaneTask<?> task) {
        task.cancel(true);
        executor.remove(task);
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
