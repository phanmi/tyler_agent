package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;

/** Allowlisted transport-to-Java exception mapping; HTTP response mapping stays in existing advice. */
public final class ProtocolFailures {
    private ProtocolFailures() {
    }

    /** Accept only a validated failure envelope; never load a class named by the worker. */
    public static RuntimeException translate(JsonNode message, MessageCodec codec) {
        codec.validateShape(message);
        codec.validateSemantics(message);
        JsonNode error = message.path("payload").path("error");
        if (error.isMissingNode()) {
            throw new ProtocolException("INVALID_MESSAGE");
        }
        String detail = error.path("message").asText();
        return switch (error.path("code").asText()) {
            case "INVALID_ARGUMENT" -> new IllegalArgumentException(detail);
            case "SQL_DATA_VALIDATION" -> new SQLDataValidationException(detail);
            case "API_KEY_MISSING" -> new OpenAIKeyException("OpenAI API key is empty; chat is unavailable");
            case "PROVIDER_AUTH" -> new OpenAIKeyException("This API key is invalid or unavailable");
            case "SQL_READ" -> new SQLReadException("Agent database read failed");
            case "SQL_WRITE" -> new SQLPersistentException("Agent database write failed");
            case "SQL_ACCESS" -> new DataAccessResourceFailureException("Agent database access failed");
            case "FILE_READ" -> new FileReadException(detail);
            case "FILE_WRITE" -> new FileWriteException(detail);
            default -> new IllegalStateException("Agent request failed: " + error.path("code").asText());
        };
    }
}
