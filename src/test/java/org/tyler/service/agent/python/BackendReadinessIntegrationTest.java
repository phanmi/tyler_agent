package org.tyler.service.agent.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.python.WorkerTestSupport.*;

class BackendReadinessIntegrationTest {
    @TempDir Path directory;

    @Test
    void realPythonHandshakePrecedesNoncePortAnnouncementWithoutApiKey() throws Exception {
        String nonce = "1234567890abcdef1234567890abcdef";
        Path log = directory.resolve("backend.log"), pidFile = directory.resolve("worker.pid");
        var builder = new ProcessBuilder(javaCommand(ReadinessOwner.class, pidFile.toString(),
                "--agent.implementation=python", "--agent.workspace-dir=" + directory.resolve("data"),
                "--agent.python.command[0]=" + python(), "--agent.python.command[1]=-m", "--agent.python.command[2]=tyler_agent",
                "--spring.main.banner-mode=off", "--logging.level.root=ERROR"));
        builder.environment().put("TYLER_STARTUP_NONCE", nonce);
        Process owner = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long child = -1;
        try {
            await(() -> Files.exists(pidFile) || !owner.isAlive());
            assertTrue(owner.isAlive(), () -> read(log));
            child = Long.parseLong(Files.readString(pidFile));
            var match = Pattern.compile("(?m)^TYLER_READY " + nonce + " (\\d+)\\r?$").matcher(read(log));
            assertTrue(match.find(), () -> read(log));
            String base = "http://127.0.0.1:" + match.group(1);
            try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
                var history = client.send(HttpRequest.newBuilder(URI.create(base + "/api/agent/history")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, history.statusCode()); assertEquals("[]", history.body());
                var chat = client.send(HttpRequest.newBuilder(URI.create(base + "/api/agent/chat"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"test\"}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, chat.statusCode());
            }
        } finally {
            owner.destroyForcibly(); assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
            if (child > 0) {
                long pid = child;
                try { await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)); }
                finally { ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly); }
            }
        }
    }

    private String read(Path path) {
        try { return Files.readString(path); } catch (java.io.IOException e) { return ""; }
    }
}
