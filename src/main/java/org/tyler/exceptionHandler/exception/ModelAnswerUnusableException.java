package org.tyler.exceptionHandler.exception;

/**
 * Thrown when the model answered every request of a run, but no answer to a question the run
 * cannot go on without could be used: the worker's {@code MODEL_ANSWER_UNUSABLE} failure.
 * Mapped to HTTP 502 by {@link org.tyler.exceptionHandler.ProviderExceptionHandler}, because the
 * fault is in what the model sent back and not in this server. A provider request that itself
 * fails is a different failure, with the generic HTTP 500.
 */
public class ModelAnswerUnusableException extends RuntimeException {

    public ModelAnswerUnusableException(String message) {
        super(message);
    }
}
