package org.tyler.service.agent.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tyler.exceptionHandler.exception.*;
import org.tyler.service.agent.protocol.MessageValidator;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.tyler.service.agent.python.WorkerTestSupport.fixture;

class WorkerMessagesTest {
    static Stream<RuntimeException> failures() {
        return Stream.of(new SQLReadException("synthetic-secret"), new SQLPersistentException("synthetic-secret"),
                new SQLDataValidationException("synthetic-secret"), new IllegalArgumentException("synthetic-secret"),
                new FileReadException("synthetic-secret"), new FileWriteException("synthetic-secret"),
                new IllegalStateException("synthetic-secret"), new OpenAIKeyException("synthetic-secret"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void errorsAreAllowlistedAndNeverContainExceptionText(RuntimeException failure) {
        var original = fixture("food_request");
        var payload = WorkerMessages.result(original.path("payload"), null, failure);
        var message = WorkerMessages.envelope(original.path("sessionId").asText(), "operation_result", "req-1", payload);
        assertDoesNotThrow(() -> new MessageValidator().validateMessage(message));
        assertFalse(message.toString().contains("synthetic-secret"));
        assertEquals("failure", payload.path("outcome").asText());
    }

    @Test
    void resultsPreserveOperationIdentityAndValue() {
        var payload = fixture("food_result").path("payload");
        assertEquals(payload, WorkerMessages.result(fixture("food_request").path("payload"), payload.path("result"), null));
    }

    @Test
    void exceptionTypeAloneDoesNotProveAWriteWasRolledBack() {
        var failure = new IllegalArgumentException("synthetic-secret");
        assertEquals("unknown", WorkerMessages.result(fixture("food_request").path("payload"), null, failure)
                .path("error").path("writeOutcome").asText());
        assertEquals("none", WorkerMessages.result(fixture("time_request").path("payload"), null, failure)
                .path("error").path("writeOutcome").asText());
    }
}
