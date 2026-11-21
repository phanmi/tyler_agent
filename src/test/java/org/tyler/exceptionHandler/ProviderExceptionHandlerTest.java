package org.tyler.exceptionHandler;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.tyler.exceptionHandler.exception.ModelAnswerUnusableException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that {@link ProviderExceptionHandler} maps an unusable model answer to HTTP 502. */
class ProviderExceptionHandlerTest {

    private final ProviderExceptionHandler handler = new ProviderExceptionHandler();

    @Test
    void modelAnswerUnusableExceptionReturns502WithItsMessage() {
        var response = handler.handleModelAnswerUnusable(
                new ModelAnswerUnusableException("The model could not complete a required check. Please try again"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("The model could not complete a required check. Please try again", response.getBody().error());
    }
}
