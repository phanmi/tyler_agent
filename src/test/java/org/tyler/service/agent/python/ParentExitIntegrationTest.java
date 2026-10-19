package org.tyler.service.agent.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.python.WorkerTestSupport.*;

/** Real Java owner and real Python watchdog: forceful exit bypasses Java shutdown hooks. */
class ParentExitIntegrationTest {
    @TempDir Path directory;

    @Test
    void normalSupervisorShutdownTerminatesBlockedPythonWork() throws Exception {
        Path pending = directory.resolve("normal-pending");
        var settings = new PythonAgentSettings(List.of(python(),
                Path.of("agent-python/tests/support/blocked_worker.py").toAbsolutePath().toString(), pending.toString()),
                Duration.ofSeconds(10), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(300), 2);
        try (var worker = new PythonWorker(settings, (name, input) -> null)) {
            var answer = CompletableFuture.supplyAsync(() -> worker.execute(payload()));
            await(() -> Files.exists(pending));
            worker.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> answer.get(3, TimeUnit.SECONDS));
            await(() -> !ProcessHandle.of(worker.pid()).map(ProcessHandle::isAlive).orElse(false));
        }
    }

    @Test
    void forcefulJavaExitTerminatesPythonDuringBlockedProviderWork() throws Exception {
        Path pending = directory.resolve("pending"), pidFile = directory.resolve("worker.pid");
        Process owner = new ProcessBuilder(javaCommand(JavaWorkerOwner.class, python(),
                Path.of("agent-python/tests/support/blocked_worker.py").toAbsolutePath().toString(),
                pending.toString(), pidFile.toString()))
                .redirectErrorStream(true).redirectOutput(directory.resolve("owner.log").toFile()).start();
        long child = -1;
        try {
            await(() -> Files.exists(pending) || !owner.isAlive());
            assertTrue(owner.isAlive(), () -> read(directory.resolve("owner.log")));
            child = Long.parseLong(Files.readString(pidFile));
            assertTrue(ProcessHandle.of(child).orElseThrow().isAlive());
            owner.destroyForcibly();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
            long pid = child;
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            owner.destroyForcibly(); owner.waitFor(5, TimeUnit.SECONDS);
            if (child > 0) ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private String read(Path path) {
        try { return Files.readString(path); } catch (java.io.IOException e) { return "No child log"; }
    }
}
