package org.tyler.exceptionHandler.exception;

/** Thrown when SQLite schema creation, insertion, updates, or deletion fail. */
public class SQLPersistentException extends RuntimeException {

    public SQLPersistentException(String message) {
        super(message);
    }

    public SQLPersistentException(String message, Throwable cause) {
        super(message, cause);
    }
}
