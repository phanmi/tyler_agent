package org.tyler.exceptionHandler;

/**
 * Shared contract for all {@code @RestControllerAdvice} exception handlers.
 * Defines the common {@link ErrorResponse} structure.
 */
public interface IExceptionHandler {

    /** Shared error response body. */
    record ErrorResponse(String error) {}
}