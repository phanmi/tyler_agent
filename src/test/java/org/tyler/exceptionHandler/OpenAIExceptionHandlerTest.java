package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link OpenAIExceptionHandler} maps OpenAIKeyException to HTTP 401.
 */
class OpenAIExceptionHandlerTest {

    private final OpenAIExceptionHandler handler = new OpenAIExceptionHandler();

    @Test
    void openAIKeyExceptionReturns401() {
        var response = handler.handleOpenAIKey(new OpenAIKeyException("This API key is invalid or unavailable"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("This API key is invalid or unavailable", response.getBody().error());
    }
}
