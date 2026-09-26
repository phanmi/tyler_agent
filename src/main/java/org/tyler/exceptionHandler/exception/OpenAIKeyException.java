package org.tyler.exceptionHandler.exception;

/**
 * Thrown when the OpenAI API key is missing, invalid, or expired.
 */
public class OpenAIKeyException extends RuntimeException {

    public OpenAIKeyException(String message) {
        super(message);
    }

    public OpenAIKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}