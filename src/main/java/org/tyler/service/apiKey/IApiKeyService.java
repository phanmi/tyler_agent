package org.tyler.service.apiKey;

/**
 * Contract for reading and saving the OpenAI API key.
 *
 * <p>Manages a plain-text sandbox file through {@code isConfigured}, {@code get},
 * and {@code save}, without exposing file paths.
 */
public interface IApiKeyService {

    /** Returns whether a nonblank API key is configured. */
    boolean isConfigured();

    /** Returns the saved API key, or an empty string if the file is missing. */
    String get();

    /** Trims and saves the API key; an empty string clears it. */
    void save(String apiKey);
}