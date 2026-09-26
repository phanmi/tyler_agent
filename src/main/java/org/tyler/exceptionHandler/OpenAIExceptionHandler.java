package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;

/**
 * Handles OpenAI API key errors.
 */
@RestControllerAdvice
public class OpenAIExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OpenAIExceptionHandler.class);

    @ExceptionHandler(OpenAIKeyException.class)
    public ResponseEntity<ErrorResponse> handleOpenAIKey(OpenAIKeyException ex) {
        log.warn("OpenAI API key validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(ex.getMessage()));
    }
}