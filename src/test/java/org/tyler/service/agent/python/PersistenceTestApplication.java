package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.tyler.TylerAgentApplication;
import org.tyler.service.apiKey.IApiKeyService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP application, isolated sandbox, and Python subprocess per test. */
final class PersistenceTestApplication implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    final ConfigurableApplicationContext context;
    final PythonWorker worker;
    final IntegrationOperationGate gate;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    PersistenceTestApplication(Path workspace) { this(workspace, "30s"); }

    PersistenceTestApplication(Path workspace, String timeout) {
        var app = new SpringApplication(TylerAgentApplication.class, FaultConfiguration.class);
        app.setRegisterShutdownHook(false);
        context = app.run("--agent.workspace-dir=" + workspace,
                "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR", "--chat.max-messages=100",
                "--openai.key-file-path=apikey.txt", "--chat.history-file-path=chat-history.json",
                "--food.database-path=food-record.sqlite", "--workout.database-path=workout-record.sqlite",
                "--agent.python.command[0]=" + WorkerTestSupport.python(),
                "--agent.python.command[1]=" + Path.of("agent-python/tests/support/persistence_worker.py").toAbsolutePath(),
                "--agent.python.run-timeout=" + timeout, "--agent.python.operation-timeout=" + timeout);
        worker = context.getBean(PythonWorker.class);
        gate = context.getBean(IntegrationOperationGate.class);
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        context.getBean(IApiKeyService.class).save("SYNTHETIC_INTEGRATION_KEY");
        assertInstanceOf(PythonAgentService.class, context.getBean(org.tyler.service.agent.IAgentService.class));
        assertTrue(ProcessHandle.of(worker.pid()).orElseThrow().isAlive());
    }

    CompletableFuture<HttpResponse<String>> chatAsync(String scenario) throws Exception {
        return client.sendAsync(request("POST", "/api/agent/chat",
                JSON.writeValueAsString(java.util.Map.of("message", scenario))), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode chat(String scenario, int status) throws Exception {
        return body(chatAsync(scenario).get(20, TimeUnit.SECONDS), status);
    }

    JsonNode http(String method, String path, String content, int status) throws Exception {
        return body(client.send(request(method, path, content), HttpResponse.BodyHandlers.ofString()), status);
    }

    JsonNode get(String path) throws Exception { return http("GET", path, null, 200); }

    static JsonNode body(HttpResponse<String> response, int status) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        return response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    private HttpRequest request(String method, String path, String content) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, content == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(content)).build();
    }

    @Override
    public void close() throws Exception {
        long pid = worker.pid();
        gate.release.countDown();
        try { context.close(); }
        finally {
            client.close();
            try { WorkerTestSupport.await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)); }
            finally { ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean
        @Primary
        IntegrationOperationGate integrationOperationGate(@Qualifier("operationDispatcher") IOperationDispatcher delegate) {
            return new IntegrationOperationGate(delegate);
        }
    }
}
