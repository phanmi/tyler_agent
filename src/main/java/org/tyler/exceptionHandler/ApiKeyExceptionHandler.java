package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;
import org.tyler.exceptionHandler.exception.ProviderAuthException;

/**
 * Handles API key errors for every provider: a missing key is a client error (400), a key the
 * provider rejects is an authorization failure (401). Messages are fixed text that never contains a key.
 */
// Specific failures must be resolved before the generic Exception advice.
@Order(0)
@RestControllerAdvice
public class ApiKeyExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyExceptionHandler.class);

    @ExceptionHandler(ApiKeyMissingException.class)
    public ResponseEntity<ErrorResponse> handleApiKey(ApiKeyMissingException ex) {
        log.warn("API key validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(ProviderAuthException.class)
    public ResponseEntity<ErrorResponse> handleProviderAuth(ProviderAuthException ex) {
        log.warn("Provider rejected the API key: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(ex.getMessage()));
    }
}