package org.tyler.model.llm;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The active provider and the model chosen for every provider. Contains no credentials.
 *
 * <p>Construction validates every model ID and requires a model for each provider.
 *
 * @param activeProvider the provider every chat uses
 * @param models an unmodifiable model ID per provider
 */
public record LlmSettings(LlmProvider activeProvider, Map<LlmProvider, String> models) {

    /** Model IDs are 1-100 characters from letters, digits, and {@code . _ : / -}. */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._:/-]{1,100}");

    public static final String INVALID_MODEL_MESSAGE =
            "Model ID must be 1-100 characters using only letters, digits, '.', '_', ':', '/' or '-'";

    public LlmSettings {
        if (activeProvider == null) {
            throw new IllegalArgumentException("Missing field: activeProvider");
        }
        if (models == null) {
            throw new IllegalArgumentException("Missing field: models");
        }
        EnumMap<LlmProvider, String> copy = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            String model = models.get(provider);
            if (model == null) {
                throw new IllegalArgumentException("Missing model for provider: " + provider.id());
            }
            requireModelId(model);
            copy.put(provider, model);
        }
        models = Collections.unmodifiableMap(copy);
    }

    /** OpenAI active, and each provider on its default model. */
    public static LlmSettings defaults() {
        EnumMap<LlmProvider, String> models = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            models.put(provider, provider.defaultModel());
        }
        return new LlmSettings(LlmProvider.OPENAI, models);
    }

    public String model(LlmProvider provider) {
        return models.get(provider);
    }

    /** @throws IllegalArgumentException with a fixed message that never echoes the value */
    public static void requireModelId(String model) {
        if (model == null || !MODEL_ID.matcher(model).matches()) {
            throw new IllegalArgumentException(INVALID_MODEL_MESSAGE);
        }
    }
}
