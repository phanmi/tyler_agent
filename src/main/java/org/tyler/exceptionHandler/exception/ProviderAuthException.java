package org.tyler.exceptionHandler.exception;

/**
 * Thrown when the provider rejects the active provider's key as invalid or unauthorized.
 * Mapped to HTTP 401 by {@link org.tyler.exceptionHandler.ApiKeyExceptionHandler}.
 */
public class ProviderAuthException extends RuntimeException {

    public ProviderAuthException(String message) {
        super(message);
    }
}
