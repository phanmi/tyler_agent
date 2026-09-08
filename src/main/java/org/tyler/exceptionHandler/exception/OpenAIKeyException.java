package org.tyler.exceptionHandler.exception;

/**
 * OpenAI API Key 缺失、错误或失效时抛出。
 */
public class OpenAIKeyException extends RuntimeException {

    public OpenAIKeyException(String message) {
        super(message);
    }

    public OpenAIKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}