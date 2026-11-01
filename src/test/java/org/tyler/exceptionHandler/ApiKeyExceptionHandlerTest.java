package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;
import org.tyler.exceptionHandler.exception.ProviderAuthException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link ApiKeyExceptionHandler} maps a missing key to HTTP 400 and a rejected key to HTTP 401.
 */
class ApiKeyExceptionHandlerTest {

    private final ApiKeyExceptionHandler handler = new ApiKeyExceptionHandler();

    @Test
    void apiKeyMissingExceptionReturns400() {
        var response = handler.handleApiKey(new ApiKeyMissingException("Claude API key is empty; chat is unavailable"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Claude API key is empty; chat is unavailable", response.getBody().error());
    }

    @Test
    void providerAuthExceptionReturns401() {
        var response = handler.handleProviderAuth(new ProviderAuthException("This API key is invalid or unavailable"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("This API key is invalid or unavailable", response.getBody().error());
    }
}
