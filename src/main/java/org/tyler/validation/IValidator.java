package org.tyler.validation;

/**
 * Validates a domain value without modifying it or performing I/O.
 * Implementations must be stateless so they can be shared by services.
 *
 * @param <T> the domain input type accepted by this validator
 */
@FunctionalInterface
public interface IValidator<T> {
    /**
     * Returns normally for valid input.
     *
     * @throws IllegalArgumentException if input violates the validator's domain contract,
     *         including null when the value is required
     */
    void validate(T value);
}
