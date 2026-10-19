package org.tyler.service.agent.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.tyler.service.agent.protocol.ProtocolException;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.python.WorkerTestSupport.*;

/** Real pipe/process tests prove callbacks, cancellation, quarantine, and cleanup rather than mocked exits. */
@ExtendWith(OutputCaptureExtension.class)
class PythonWorkerTest {
    static Stream<RuntimeException> localStorageFailures() {
        var cause = new java.io.IOException("synthetic-private-file-content");
        return Stream.of(new SQLReadException("synthetic-secret-sql", cause),
                new SQLPersistentException("synthetic-secret-sql", cause),
                new FileReadException("synthetic-private-path", cause),
                new FileWriteException("synthetic-private-path", cause));
    }

    @ParameterizedTest
    @MethodSource("localStorageFailures")
    void localFailureRetainsOriginalCauseWithoutExposingItsMessage(RuntimeException original) {
        try (var worker = new PythonWorker(settings("operation_then_remote_failure"), (name, input) -> {
            throw original;
        })) {
            RuntimeException failure = assertThrows(original.getClass(), () -> worker.execute(payload()));
            assertSame(original, failure.getCause());
            assertSame(original.getCause(), failure.getCause().getCause());
            assertArrayEquals(original.getStackTrace(), failure.getCause().getStackTrace());
            assertFalse(failure.getMessage().contains("synthetic-"));
            var fileAdvice = new org.tyler.exceptionHandler.FileExceptionHandler();
            var sqlAdvice = new org.tyler.exceptionHandler.SqlExceptionHandler();
            var response = switch (failure) {
                case FileReadException read -> fileAdvice.handleFileRead(read);
                case FileWriteException write -> fileAdvice.handleFileWrite(write);
                case SQLReadException read -> sqlAdvice.handleSqlRead(read);
                case SQLPersistentException write -> sqlAdvice.handleSqlPersistent(write);
                default -> throw new AssertionError("Unexpected storage failure type");
            };
            assertFalse(response.getBody().toString().contains("synthetic-"));

            // A subsequent remote-only failure must not acquire the previous request's cause.
            RuntimeException next = assertThrowsExactly(SQLReadException.class, () -> worker.execute(payload()));
            assertNull(next.getCause());
        }
    }

    @ParameterizedTest
    @CsvSource({"startup_exit, WORKER_UNAVAILABLE", "mismatch, UNSUPPORTED_VERSION"})
    void rejectsFailedStartupAndKillsProcess(String mode, String code) throws Exception {
        try (var worker = new PythonWorker(settings(mode), (name, input) -> null)) {
            assertEquals(code, assertThrows(ProtocolException.class, worker::start).code());
            await(() -> !ProcessHandle.of(worker.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void startupDeadlineAndCancellationCleanUp() throws Exception {
        var base = settings("no_ready");
        var shortStartup = new PythonAgentSettings(base.command(), Duration.ofMillis(250), base.queueTimeout(),
                base.runTimeout(), base.operationTimeout(), base.shutdownGrace(), base.queueCapacity());
        try (var worker = new PythonWorker(shortStartup, (name, input) -> null)) {
            assertEquals("DEADLINE_EXCEEDED", assertThrows(ProtocolException.class, worker::start).code());
            await(() -> !ProcessHandle.of(worker.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
        try (var worker = new PythonWorker(base, (name, input) -> null)) {
            var startup = CompletableFuture.runAsync(worker::start);
            await(() -> worker.pid() > 0);
            worker.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> startup.get(3, TimeUnit.SECONDS));
            await(() -> !ProcessHandle.of(worker.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void callbackRunsWhileCallerWaits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var worker = new PythonWorker(settings("operation"), (name, input) -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError("Services must not be interrupted", e); }
            return fixture("time_result").path("payload").path("result");
        })) {
            var answer = CompletableFuture.supplyAsync(() -> worker.execute(payload()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse(answer.isDone());
            release.countDown();
            assertNotNull(answer.get(5, TimeUnit.SECONDS));
            worker.historySettled(true);
        } finally { release.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"hang", "ignore_cancel", "late"})
    void deadlineRejectsLateRepliesAndTerminatesWorker(String mode) throws Exception {
        var base = settings(mode);
        var bounded = new PythonAgentSettings(base.command(), base.startupTimeout(), base.queueTimeout(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), base.shutdownGrace(), base.queueCapacity());
        try (var worker = new PythonWorker(bounded, (name, input) -> { fail("No operation was authorized"); return null; })) {
            assertEquals("DEADLINE_EXCEEDED", assertThrows(ProtocolException.class, () -> worker.execute(payload())).code());
            await(() -> !ProcessHandle.of(worker.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void lostWriteResultBlocksNewRunsUntilActualServiceSettlementWithoutReplay() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), settled = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var worker = new PythonWorker(settings("write"), (name, input) -> {
            calls.incrementAndGet(); entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            settled.countDown();
            return fixture("food_result").path("payload").path("result");
        })) {
            var answer = CompletableFuture.supplyAsync(() -> worker.execute(payload()));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            ProcessHandle.of(worker.pid()).orElseThrow().destroyForcibly();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> answer.get(5, TimeUnit.SECONDS));
            assertEquals("WORKER_UNAVAILABLE", assertThrows(ProtocolException.class, () -> worker.execute(payload())).code());
            assertEquals(1, calls.get());
            release.countDown();
            assertTrue(settled.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "ignore_shutdown"})
    void shutdownIsBoundedAndIdempotent(String mode) throws Exception {
        var worker = new PythonWorker(settings(mode), (name, input) -> null);
        try {
            worker.start();
            long pid = worker.pid();
            worker.close(); worker.close();
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertEquals("WORKER_UNAVAILABLE", assertThrows(ProtocolException.class, worker::start).code());
        } finally { worker.close(); }
    }

    @Test
    void untrustedStderrIsDrainedWithoutForwardingSecrets(CapturedOutput output) {
        try (var worker = new PythonWorker(settings("stderr"), (name, input) -> null)) {
            assertNotNull(worker.execute(payload()));
            worker.historySettled(true);
        }
        assertFalse(output.getAll().contains("synthetic-secret-private-history"));
    }

    @Test
    void duplicateOperationCannotExecuteTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        try (var worker = new PythonWorker(settings("duplicate"), (name, input) -> {
            calls.incrementAndGet();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new AssertionError(e); }
            return fixture("time_result").path("payload").path("result");
        })) {
            assertEquals("DUPLICATE_MESSAGE", assertThrows(ProtocolException.class, () -> worker.execute(payload())).code());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test
    void interruptingTheWaitingCallerCancelsWithoutAcceptingLateTools() throws Exception {
        try (var worker = new PythonWorker(settings("late"), (name, input) -> {
            fail("Cancelled run must not dispatch another tool"); return null;
        })) {
            worker.start();
            var outcome = new CompletableFuture<String>();
            Thread caller = Thread.ofVirtual().start(() -> {
                try { worker.execute(payload()); }
                catch (ProtocolException e) { outcome.complete(e.code()); }
            });
            // Interrupt is also safe if it races with registration of the run.
            caller.interrupt();
            assertEquals("REQUEST_CANCELLED", outcome.get(5, TimeUnit.SECONDS));
            await(() -> !ProcessHandle.of(worker.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void operationTimeoutDoesNotInterruptOrReplayServiceWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), settled = new CountDownLatch(1);
        try (var worker = new PythonWorker(settings("write"), (name, input) -> {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError("Transaction was interrupted", e); }
            settled.countDown();
            return fixture("food_result").path("payload").path("result");
        })) {
            var outcome = CompletableFuture.supplyAsync(() -> worker.execute(payload()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var expired = assertThrows(java.util.concurrent.ExecutionException.class, () -> outcome.get(4, TimeUnit.SECONDS));
            assertEquals("DEADLINE_EXCEEDED", ((ProtocolException) expired.getCause()).code());
            assertEquals(1, settled.getCount());
            assertEquals("WORKER_UNAVAILABLE", assertThrows(ProtocolException.class, () -> worker.execute(payload())).code());
            release.countDown();
            assertTrue(settled.await(5, TimeUnit.SECONDS));
        } finally { release.countDown(); }
    }
}
