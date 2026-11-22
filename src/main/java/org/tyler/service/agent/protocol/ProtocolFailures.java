package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;
import org.tyler.exceptionHandler.exception.ModelAnswerUnusableException;
import org.tyler.exceptionHandler.exception.ProviderAuthException;
import org.tyler.exceptionHandler.exception.SQLDataValidationException;
import org.tyler.exceptionHandler.exception.SQLPersistentException;
import org.tyler.exceptionHandler.exception.SQLReadException;

/** Allowlisted transport-to-Java exception mapping; HTTP response mapping stays in existing advice. */
public final class ProtocolFailures {
    /** The model answered, but no answer to a question the run needs could be used. */
    static final String MODEL_ANSWER_UNUSABLE = "MODEL_ANSWER_UNUSABLE";
    /** The session's write outcome for a run that saved nothing and has no write still open. */
    private static final String NOTHING_SAVED = "none";
    private static final String CHECK_FAILED =
            "The model could not complete a required check. Please try again";
    private static final String CHECK_FAILED_AFTER_SAVE = "The model could not complete a required check. "
            + "Some foods from this message may already be saved. Check the Calendar before sending it again";

    private ProtocolFailures() {
    }

    /**
     * Map an accepted failure by its code only; worker detail must never reach advice logs or HTTP.
     * {@code writeOutcome} is Java's own record of what the run saved, from the session's decision
     * on this message. The outcome the failure itself carries is the worker's claim and is not read.
     */
    public static RuntimeException translate(JsonNode message, MessageCodec codec, String writeOutcome) {
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
            case "PROVIDER_AUTH" -> new ProviderAuthException("This API key is invalid or unavailable");
            case MODEL_ANSWER_UNUSABLE -> new ModelAnswerUnusableException(checkFailedText(writeOutcome));
            case "SQL_READ" -> new SQLReadException("Agent database read failed");
            case "SQL_WRITE" -> new SQLPersistentException("Agent database write failed");
            case "SQL_ACCESS" -> new DataAccessResourceFailureException("Agent database access failed");
            case "FILE_READ" -> new FileReadException("Agent file read failed");
            case "FILE_WRITE" -> new FileWriteException("Agent file write failed");
            default -> new IllegalStateException("Agent request failed: " + error.path("code").asText());
        };
    }

    /**
     * A failed message is not added to history, so sending it again repeats every step of it. The
     * text suggests that only when the run saved nothing. A write that is confirmed, or still open,
     * gets the warning instead.
     */
    private static String checkFailedText(String writeOutcome) {
        return writeOutcome.equals(NOTHING_SAVED) ? CHECK_FAILED : CHECK_FAILED_AFTER_SAVE;
    }
}
