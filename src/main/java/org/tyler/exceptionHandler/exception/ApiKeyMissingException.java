package org.tyler.exceptionHandler.exception;

/**
 * Thrown when the active provider's API key is missing, or the provider rejects it as
 * invalid or unauthorized. Mapped to HTTP 401 by {@link org.tyler.exceptionHandler.ApiKeyExceptionHandler}.
 */
public class ApiKeyMissingException extends RuntimeException {

    public ApiKeyMissingException(String message) {
        super(message);
    }

    public ApiKeyMissingException(String message, Throwable cause) {
        super(message, cause);
    }
}