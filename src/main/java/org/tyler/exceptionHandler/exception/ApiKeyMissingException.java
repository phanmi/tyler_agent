package org.tyler.exceptionHandler.exception;

/**
 * Thrown when the active provider has no saved API key, before any worker or provider call.
 * Mapped to HTTP 400 by {@link org.tyler.exceptionHandler.ApiKeyExceptionHandler}: the request
 * cannot run until the user saves a key, which is distinct from the access-token 401.
 */
public class ApiKeyMissingException extends RuntimeException {

    public ApiKeyMissingException(String message) {
        super(message);
    }

    public ApiKeyMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}
