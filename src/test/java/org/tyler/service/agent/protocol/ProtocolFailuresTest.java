package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.exceptionHandler.FileExceptionHandler;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.exceptionHandler.OpenAIExceptionHandler;
import org.tyler.exceptionHandler.SqlExceptionHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real Java advice keeps public status/body contracts separate from private worker failures. */
class ProtocolFailuresTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageCodec CODEC = new MessageCodec();

    @ParameterizedTest
    @CsvSource({
            "validation, INVALID_ARGUMENT, 400, Safe detail, IllegalArgumentException",
            "validation, SQL_DATA_VALIDATION, 400, Safe detail, SQLDataValidationException",
            "authentication, API_KEY_MISSING, 401, OpenAI API key is empty; chat is unavailable, OpenAIKeyException",
            "authentication, PROVIDER_AUTH, 401, This API key is invalid or unavailable, OpenAIKeyException",
            "storage, SQL_READ, 500, Database operation failed. Please try again later, SQLReadException",
            "storage, SQL_WRITE, 500, Database operation failed. Please try again later, SQLPersistentException",
            "storage, SQL_ACCESS, 500, Database operation failed. Please try again later, DataAccessResourceFailureException",
            "storage, FILE_READ, 500, Failed to read file: Safe detail, FileReadException",
            "storage, FILE_WRITE, 500, Failed to write file: Safe detail, FileWriteException",
            "provider, PROVIDER_FAILURE, 500, Internal server error. Please try again later, IllegalStateException",
            "worker, WORKER_UNAVAILABLE, 500, Internal server error. Please try again later, IllegalStateException",
            "timeout, DEADLINE_EXCEEDED, 500, Internal server error. Please try again later, IllegalStateException",
            "cancelled, REQUEST_CANCELLED, 500, Internal server error. Please try again later, IllegalStateException",
            "protocol, INVALID_FRAME, 500, Internal server error. Please try again later, IllegalStateException"
    })
    void preservesExceptionTypeAndPublicHttpError(String category, String code, int status,
                                                 String body, String exceptionType) throws Exception {
        RuntimeException failure = ProtocolFailures.translate(failure(category, code), CODEC);
        assertEquals(exceptionType, failure.getClass().getSimpleName());
        var mvc = MockMvcBuilders.standaloneSetup(new ThrowingController(failure))
                .setControllerAdvice(new SqlExceptionHandler(), new FileExceptionHandler(),
                        new OpenAIExceptionHandler(), new GenericExceptionHandler()).build();
        mvc.perform(get("/protocol-test")).andExpect(status().is(status))
                .andExpect(content().json(MAPPER.createObjectNode().put("error", body).toString()));
    }

    @Test
    void rejectsUntrustedErrorCodesBeforeMapping() {
        ProtocolException error = assertThrowsExactly(ProtocolException.class,
                () -> ProtocolFailures.translate(failure("validation", "arbitrary.ClassName"), CODEC));
        assertEquals("INVALID_MESSAGE", error.code());
    }

    private static ObjectNode failure(String category, String code) {
        ObjectNode message = MAPPER.createObjectNode().put("protocolVersion", 1).put("type", "failed")
                .put("sessionId", "0123456789abcdef0123456789abcdef").put("requestId", "req-1");
        message.putObject("payload").putObject("error").put("category", category).put("code", code)
                .put("message", "Safe detail").put("writeOutcome", "none");
        return message;
    }

    @RestController
    static class ThrowingController {
        private final RuntimeException failure;
        ThrowingController(RuntimeException failure) { this.failure = failure; }
        @GetMapping("/protocol-test")
        public String get() { throw failure; }
    }
}
