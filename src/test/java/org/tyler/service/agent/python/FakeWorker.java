package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Real child JVM with deterministic protocol scenarios; it never loads credentials or services. */
public final class FakeWorker {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static String request;

    private FakeWorker() { }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        if (mode.equals("startup_exit")) return;
        if (!mode.equals("no_ready")) {
            ObjectNode ready = WorkerTestSupport.fixture("ready");
            if (mode.equals("mismatch")) ready.put("protocolVersion", 2);
            send(ready);
        }
        if (mode.equals("stderr")) {
            System.err.print("synthetic-secret-private-history".repeat(10000));
            System.err.flush();
        }
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonNode message = MAPPER.readTree(line);
                switch (message.path("type").asText()) {
                    case "shutdown" -> { if (!mode.equals("ignore_shutdown")) return; }
                    case "run" -> run(mode, message);
                    case "operation_result" -> result(message);
                    case "cancel" -> {
                        if (!mode.equals("ignore_cancel")) {
                            if (mode.equals("late")) send(WorkerTestSupport.fixture("time_request"));
                            send(WorkerTestSupport.fixture("failed_cancelled"));
                            if (mode.equals("late")) send(WorkerTestSupport.fixture("completed"));
                        }
                    }
                    default -> throw new IllegalStateException("Unexpected test message");
                }
            }
        }
    }

    private static void run(String mode, JsonNode message) throws Exception {
        request = message.path("requestId").asText();
        switch (mode) {
            case "crash" -> System.exit(3);
            case "hang", "ignore_cancel", "late" -> { }
            case "operation", "duplicate", "write" -> {
                ObjectNode call = WorkerTestSupport.fixture(mode.equals("write") ? "food_request" : "time_request");
                send(call);
                if (mode.equals("duplicate")) send(call);
            }
            case "failed_provider", "failed_authentication", "failed_storage" -> send(WorkerTestSupport.fixture(mode));
            default -> {
                ObjectNode completed = WorkerTestSupport.fixture("completed");
                JsonNode payload = message.path("payload");
                ((ObjectNode) completed.path("payload")).put("reply", payload.path("message").asText()
                        + ":" + payload.path("history").size() + ":" + payload.path("apiKey").asText());
                send(completed);
            }
        }
    }

    private static void result(JsonNode message) throws Exception {
        ObjectNode response = WorkerTestSupport.fixture(message.path("payload").path("outcome").asText().equals("success")
                ? "completed" : "failed_provider");
        if (response.path("type").asText().equals("failed")) {
            ((ObjectNode) response.path("payload")).set("error", message.path("payload").path("error"));
        }
        send(response);
    }

    private static void send(ObjectNode message) throws Exception {
        message.put("sessionId", System.getenv("TYLER_AGENT_SESSION_ID"));
        if (!message.path("type").asText().equals("ready")) message.put("requestId", request);
        System.out.write(MAPPER.writeValueAsBytes(message));
        System.out.write('\n');
        System.out.flush();
    }
}
