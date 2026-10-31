package org.tyler.service.llm;

import org.tyler.model.llm.LlmSettings;

/**
 * Contract for the active provider and each provider's model.
 *
 * <p>The settings are not secret; keys are managed by {@link ILlmKeyService}.
 */
public interface ILlmSettingsService {

    /**
     * Returns the saved settings, or {@link LlmSettings#defaults()} if none are saved.
     * A provider missing from the saved file uses its default model.
     *
     * @throws org.tyler.exceptionHandler.exception.FileReadException if the saved file cannot be
     *         read or is invalid; the backend never silently falls back to another provider
     */
    LlmSettings get();

    /**
     * Saves complete settings, replacing the file, and returns them. Saving valid settings
     * also repairs an invalid file.
     *
     * @throws IllegalArgumentException if the settings are missing
     */
    LlmSettings save(LlmSettings settings);
}
