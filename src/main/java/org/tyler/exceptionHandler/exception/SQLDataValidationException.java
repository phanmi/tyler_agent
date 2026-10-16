package org.tyler.exceptionHandler.exception;

/**
 * Raised when validation fails before a database write.
 *
 * <p>DAOs retain defensive checks for direct callers, including required columns.
 * This exception identifies rejected persistence input, not a database outage;
 * the handler maps it to HTTP 400.
 *
 * <p>Extends {@link IllegalArgumentException} to reuse invalid-argument handling.
 */
public class SQLDataValidationException extends IllegalArgumentException {

    public SQLDataValidationException(String message) {
        super(message);
    }

    public SQLDataValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
