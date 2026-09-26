package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Handles validation failures, unexpected errors, and missing resources.
 */
@RestControllerAdvice
public class GenericExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GenericExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException ex) {
        log.warn("Request validation failed: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        // Invalid JSON or incompatible field types prevent request-body deserialization.
        // Return HTTP 400 for invalid client input.
        log.warn("Failed to parse request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(new ErrorResponse("Invalid request body format or field type"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleServerError(Exception ex) {
        log.error("Unexpected server error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal server error. Please try again later"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("Resource not found"));
    }
}