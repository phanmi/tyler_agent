package org.tyler.exceptionHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Handles validation failures, unexpected errors, and missing resources.
 */
@RestControllerAdvice
public class GenericExceptionHandler implements IExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GenericExceptionHandler.class);

    // Binding happens before controller/service validation; these are still client input errors.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("Required request parameter is missing: {}", ex.getParameterName());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Missing required parameter: " + ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        // Do not echo raw input or conversion internals into logs or the public response.
        log.warn("Request parameter has an invalid type: {}", ex.getName());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Invalid format or value for parameter: " + ex.getName()));
    }

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
