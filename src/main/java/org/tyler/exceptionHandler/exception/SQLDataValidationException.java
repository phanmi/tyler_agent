package org.tyler.exceptionHandler.exception;

/**
 * Raised when validation fails before a database write.
 *
 * <p>Required columns in {@code food_record} must be validated before insertion.
 * Missing fields in {@link org.tyler.model.food.Food} raise this exception
 * instead of being replaced with defaults; the handler maps it to HTTP 400.
 *
 * <p>Extends {@link IllegalArgumentException} to reuse invalid-argument handling.
 */
public class SQLDataValidationException extends IllegalArgumentException {

    public SQLDataValidationException(String message) {
        super(message);
    }
}