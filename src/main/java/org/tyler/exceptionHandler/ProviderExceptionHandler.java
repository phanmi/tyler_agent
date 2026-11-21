package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tyler.exceptionHandler.exception.ModelAnswerUnusableException;

/**
 * Handles model provider failures that have a message of their own for the user. A provider
 * request that fails has none and ends in the generic HTTP 500; a rejected key is handled by
 * {@link ApiKeyExceptionHandler}. Messages are fixed text with no model or request content.
 */
// Specific failures must be resolved before the generic Exception advice.
@Order(0)
@RestControllerAdvice
public class ProviderExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProviderExceptionHandler.class);

    @ExceptionHandler(ModelAnswerUnusableException.class)
    public ResponseEntity<ErrorResponse> handleModelAnswerUnusable(ModelAnswerUnusableException ex) {
        log.warn("The model's answers could not be used: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(ex.getMessage()));
    }
}
