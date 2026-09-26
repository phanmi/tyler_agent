package org.tyler.exceptionHandler.exception;

/**
 * Thrown when a workspace file cannot be read.
 */
public class FileReadException extends RuntimeException {

    public FileReadException(String message) {
        super(message);
    }

    public FileReadException(String message, Throwable cause) {
        super(message, cause);
    }
}