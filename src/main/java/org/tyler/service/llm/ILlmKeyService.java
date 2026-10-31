package org.tyler.service.llm;

import org.tyler.model.llm.LlmProvider;

/**
 * Contract for reading and saving one API key per provider.
 *
 * <p>Each key lives in its own plain-text sandbox file. Callers outside the agent run
 * only learn whether a key is configured, never its value or file path.
 */
public interface ILlmKeyService {

    /** Returns whether a nonblank key is saved for the provider. */
    boolean isConfigured(LlmProvider provider);

    /** Returns the provider's saved key, or an empty string if none is saved. */
    String get(LlmProvider provider);

    /**
     * Trims and saves the provider's key; a null or blank key clears it.
     *
     * @throws IllegalArgumentException if the trimmed key is longer than 512 characters or
     *         contains anything but visible ASCII ({@code !} to {@code ~}); the existing key is unchanged
     */
    void save(LlmProvider provider, String apiKey);
}
