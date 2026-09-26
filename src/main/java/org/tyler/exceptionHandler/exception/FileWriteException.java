package org.tyler.exceptionHandler.exception;

/**
 * Thrown when a workspace file cannot be written.
 */
public class FileWriteException extends RuntimeException {

    public FileWriteException(String message) {
        super(message);
    }

    public FileWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}