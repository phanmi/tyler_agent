package org.tyler.validation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Shared input rules; invalid domain values are arguments, not storage failures. */
public final class DomainValidation {
    private DomainValidation() {
    }

    public static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
    }

    public static void requireNonnegative(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Missing field: " + field);
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Field " + field + " must not be negative");
        }
    }

    /** Parses a real ISO calendar date without trimming or silently normalizing input. */
    public static LocalDate parseDate(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must use the format YYYY-MM-DD", e);
        }
    }

    public static void requireId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be greater than 0");
        }
    }
}
