package org.tyler.service.agent.python;

import org.junit.jupiter.api.Test;
import org.tyler.service.agent.protocol.ProtocolException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.python.WorkerTestSupport.*;

class ConversationLaneTest {
    @Test
    void boundedQueueExpiresWithoutRunningTheExpiredAction() throws Exception {
        var base = settings("complete");
        var bounded = new PythonAgentSettings(base.command(), base.startupTimeout(), Duration.ofMillis(500),
                base.runTimeout(), base.operationTimeout(), base.shutdownGrace(), 1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean executed = new AtomicBoolean();
        try (var lane = new ConversationLane(bounded)) {
            var first = CompletableFuture.supplyAsync(() -> lane.call(() -> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return "first";
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var second = CompletableFuture.supplyAsync(() -> lane.call(() -> { executed.set(true); return "second"; }));
            await(() -> lane.queuedRequests() == 1);
            assertEquals("WORKER_BUSY", assertThrows(ProtocolException.class, () -> lane.call(() -> "third")).code());
            var expired = assertThrows(java.util.concurrent.ExecutionException.class, () -> second.get(3, TimeUnit.SECONDS));
            assertEquals("DEADLINE_EXCEEDED", ((ProtocolException) expired.getCause()).code());
            assertFalse(executed.get());
            release.countDown();
            assertEquals("first", first.get(3, TimeUnit.SECONDS));
            assertEquals("nested", lane.call(() -> lane.call(() -> "nested")));
        } finally { release.countDown(); }
    }

    @Test
    void callerInterruptionCancelsQueuedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean();
        try (var lane = new ConversationLane(settings("complete"))) {
            var first = CompletableFuture.supplyAsync(() -> lane.call(() -> {
                entered.countDown(); release.await(5, TimeUnit.SECONDS); return "first";
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var cancelled = new CompletableFuture<String>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try { lane.call(() -> { ran.set(true); return "queued"; }); }
                catch (ProtocolException e) { cancelled.complete(e.code()); }
            });
            await(() -> lane.queuedRequests() == 1);
            caller.interrupt();
            assertEquals("REQUEST_CANCELLED", cancelled.get(3, TimeUnit.SECONDS));
            release.countDown(); first.get(3, TimeUnit.SECONDS);
            assertFalse(ran.get());
            lane.close();
            assertEquals("WORKER_UNAVAILABLE", assertThrows(ProtocolException.class, () -> lane.call(() -> "closed")).code());
        } finally { release.countDown(); }
    }
}
