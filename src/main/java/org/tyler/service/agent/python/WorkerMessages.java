package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataAccessException;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;

/**
 * Builds private transport envelopes and code-owned failures; never serializes arbitrary exceptions.
 * The worker's Java run ledger retains original local failures separately from these wire payloads.
 */
final class WorkerMessages {
    private WorkerMessages() { }

    static ObjectNode envelope(String session, String type, String request, JsonNode payload) {
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("protocolVersion", 1)
                .put("sessionId", session).put("type", type).put("requestId", request);
        message.set("payload", payload);
        return message;
    }

    static ObjectNode reason(String reason) {
        return JsonNodeFactory.instance.objectNode().put("reason", reason);
    }

    static ObjectNode result(JsonNode request, JsonNode result, RuntimeException failure) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode()
                .put("operationId", request.path("operationId").asText())
                .put("operation", request.path("operation").asText());
        if (failure == null) {
            payload.put("outcome", "success").set("result", result);
        } else {
            ObjectNode error = error(failure);
            if (!java.util.Set.of("food.insert", "workout_plan.generate_save", "file.write")
                    .contains(request.path("operation").asText())) error.put("writeOutcome", "none");
            payload.put("outcome", "failure").set("error", error);
        }
        return payload;
    }

    private static ObjectNode error(RuntimeException failure) {
        String code = switch (failure) {
            case SQLDataValidationException ignored -> "SQL_DATA_VALIDATION";
            case IllegalArgumentException ignored -> "INVALID_ARGUMENT";
            case OpenAIKeyException ignored -> "API_KEY_MISSING";
            case SQLReadException ignored -> "SQL_READ";
            case SQLPersistentException ignored -> "SQL_WRITE";
            case DataAccessException ignored -> "SQL_ACCESS";
            case FileReadException ignored -> "FILE_READ";
            case FileWriteException ignored -> "FILE_WRITE";
            default -> "WORKER_UNAVAILABLE";
        };
        String category = switch (code) {
            case "INVALID_ARGUMENT", "SQL_DATA_VALIDATION" -> "validation";
            case "API_KEY_MISSING" -> "authentication";
            case "WORKER_UNAVAILABLE" -> "worker";
            default -> "storage";
        };
        return JsonNodeFactory.instance.objectNode().put("category", category).put("code", code)
                .put("message", "Java operation failed")
                // A thrown exception after dispatch does not establish transaction rollback.
                .put("writeOutcome", "unknown");
    }
}
