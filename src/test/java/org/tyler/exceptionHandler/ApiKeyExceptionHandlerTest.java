package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link ApiKeyExceptionHandler} maps ApiKeyMissingException to HTTP 401.
 */
class ApiKeyExceptionHandlerTest {

    private final ApiKeyExceptionHandler handler = new ApiKeyExceptionHandler();

    @Test
    void apiKeyMissingExceptionReturns401() {
        var response = handler.handleApiKey(new ApiKeyMissingException("This API key is invalid or unavailable"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("This API key is invalid or unavailable", response.getBody().error());
    }
}
