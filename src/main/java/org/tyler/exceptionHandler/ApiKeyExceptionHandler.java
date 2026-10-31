package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;

/**
 * Handles API key errors for every provider.
 */
// Specific failures must be resolved before the generic Exception advice.
@Order(0)
@RestControllerAdvice
public class ApiKeyExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyExceptionHandler.class);

    @ExceptionHandler(ApiKeyMissingException.class)
    public ResponseEntity<ErrorResponse> handleApiKey(ApiKeyMissingException ex) {
        // Messages are fixed text that never contains a key.
        log.warn("API key validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(ex.getMessage()));
    }
}