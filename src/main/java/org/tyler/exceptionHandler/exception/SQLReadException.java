package org.tyler.exceptionHandler.exception;

/** Thrown when database records or SQL resources cannot be read. */
public class SQLReadException extends RuntimeException {

    public SQLReadException(String message) {
        super(message);
    }

    public SQLReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
