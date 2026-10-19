package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic fixture data and bounded subprocess waits shared by lifecycle tests. */
public final class WorkerTestSupport {
    private WorkerTestSupport() { }

    public static List<String> javaCommand(Class<?> main, String... args) {
        var command = new java.util.ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), main.getName()));
        command.addAll(List.of(args));
        return command;
    }

    public static PythonAgentSettings settings(String mode) {
        return new PythonAgentSettings(javaCommand(FakeWorker.class, mode), Duration.ofSeconds(10),
                Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofMillis(300), 2);
    }

    public static ObjectNode fixture(String name) {
        try (var input = WorkerTestSupport.class.getResourceAsStream("/agent-protocol/fixtures/messages.json")) {
            assertNotNull(input);
            for (var item : new ObjectMapper().readTree(input).path("cases")) {
                if (item.path("id").asText().equals(name)) return (ObjectNode) item.path("message").deepCopy();
            }
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        throw new IllegalArgumentException("Missing test fixture");
    }

    public static ObjectNode payload() { return (ObjectNode) fixture("run").path("payload"); }

    public static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not settle within the bounded wait");
    }

    public static String python() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "Scripts/python.exe" : "bin/python";
        Path path = Path.of("agent-python/.venv", name).toAbsolutePath();
        assertTrue(java.nio.file.Files.isRegularFile(path), "Run uv sync --locked in agent-python before Maven lifecycle tests");
        return path.toString();
    }
}
