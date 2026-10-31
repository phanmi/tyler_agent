package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;

/** Allowlisted transport-to-Java exception mapping; HTTP response mapping stays in existing advice. */
public final class ProtocolFailures {
    private ProtocolFailures() {
    }

    /** Map an accepted failure by code only; worker detail must never reach advice logs or HTTP. */
    public static RuntimeException translate(JsonNode message, MessageCodec codec) {
        codec.validator().validateMessage(message);
        JsonNode error = message.path("payload").path("error");
        if (error.isMissingNode()) {
            throw new ProtocolException("INVALID_MESSAGE");
        }
        // Schema validation cannot make arbitrary worker text safe, including in exception causes.
        // Keep remote detail out of Java exceptions; the validated code identifies the failure.
        return switch (error.path("code").asText()) {
            case "INVALID_ARGUMENT" -> new IllegalArgumentException("Invalid agent request");
            case "SQL_DATA_VALIDATION" -> new SQLDataValidationException("Invalid agent record data");
            // The code carries no provider; Java names the provider in its own pre-run check.
            case "API_KEY_MISSING" -> new ApiKeyMissingException("API key is empty; chat is unavailable");
            case "PROVIDER_AUTH" -> new ApiKeyMissingException("This API key is invalid or unavailable");
            case "SQL_READ" -> new SQLReadException("Agent database read failed");
            case "SQL_WRITE" -> new SQLPersistentException("Agent database write failed");
            case "SQL_ACCESS" -> new DataAccessResourceFailureException("Agent database access failed");
            case "FILE_READ" -> new FileReadException("Agent file read failed");
            case "FILE_WRITE" -> new FileWriteException("Agent file write failed");
            default -> new IllegalStateException("Agent request failed: " + error.path("code").asText());
        };
    }
}
