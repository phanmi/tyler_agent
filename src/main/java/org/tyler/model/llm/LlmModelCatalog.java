package org.tyler.model.llm;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Each provider's default, suggested, and low-cost models, from {@code llm-models.properties}.
 *
 * <p>The eval harness reads the same file, so Settings and the evals offer the same models.
 * Construction checks every provider's entries, so a mistake in the file stops the backend at
 * startup with the name of the property that is wrong. The catalog never changes afterwards.
 *
 * @param providers an unmodifiable entry for every provider
 */
public record LlmModelCatalog(Map<LlmProvider, ProviderModels> providers) {

    /** The prefix of every property in {@code llm-models.properties}. */
    public static final String PROPERTY_PREFIX = "llm.models.";

    /**
     * One provider's models.
     *
     * @param defaultModel the model the provider uses until the user picks another
     * @param suggested the Settings picker's suggestions, default first
     * @param cheap the low-cost suggestion that the eval harness's {@code --cheap-models} runs
     * @param labels the name Settings shows for a suggested model instead of its ID, by model ID;
     *               a model without one is shown by its ID
     */
    public record ProviderModels(String defaultModel, List<String> suggested, String cheap,
                                 Map<String, String> labels) {

        public ProviderModels {
            suggested = List.copyOf(suggested);
            labels = Map.copyOf(labels);
        }
    }

    public LlmModelCatalog {
        EnumMap<LlmProvider, ProviderModels> copy = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            ProviderModels models = providers.get(provider);
            requireValid(provider, models);
            requireValidLabels(provider, models);
            copy.put(provider, models);
        }
        providers = Collections.unmodifiableMap(copy);
    }

    public String defaultModel(LlmProvider provider) {
        return providers.get(provider).defaultModel();
    }

    /** Suggestions for the Settings picker, default first; any valid model ID is also accepted. */
    public List<String> suggestedModels(LlmProvider provider) {
        return providers.get(provider).suggested();
    }

    /**
     * The name Settings shows for a suggested model instead of its ID, by model ID. A model
     * without an entry is shown by its ID. Chats and the settings file always use the ID.
     */
    public Map<String, String> modelLabels(LlmProvider provider) {
        return providers.get(provider).labels();
    }

    /** OpenAI active, and each provider on its default model. */
    public LlmSettings defaultSettings() {
        EnumMap<LlmProvider, String> models = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            models.put(provider, defaultModel(provider));
        }
        return new LlmSettings(LlmProvider.OPENAI, models);
    }

    private static void requireValid(LlmProvider provider, ProviderModels models) {
        if (models.suggested().isEmpty()) {
            throw problem(provider, "suggested", "must list at least one model");
        }
        for (String model : models.suggested()) {
            try {
                LlmSettings.requireModelId(model);
            } catch (IllegalArgumentException e) {
                throw problem(provider, "suggested", "has an invalid model ID '" + model + "'");
            }
        }
        if (!models.suggested().getFirst().equals(models.defaultModel())) {
            throw problem(provider, "default", "must be the first of " + property(provider, "suggested"));
        }
        // List.copyOf lists reject contains(null), so a missing entry is checked first.
        if (models.cheap() == null || !models.suggested().contains(models.cheap())) {
            throw problem(provider, "cheap", "must be one of " + property(provider, "suggested"));
        }
    }

    /** A label left behind for a model that is no longer suggested would otherwise go unnoticed. */
    private static void requireValidLabels(LlmProvider provider, ProviderModels models) {
        models.labels().forEach((model, label) -> {
            String field = "label[" + model + "]";
            if (!models.suggested().contains(model)) {
                throw problem(provider, field, "must name one of " + property(provider, "suggested"));
            }
            if (label.isBlank()) {
                throw problem(provider, field, "must not be blank");
            }
        });
    }

    private static IllegalArgumentException problem(LlmProvider provider, String field, String rule) {
        return new IllegalArgumentException(property(provider, field) + " " + rule);
    }

    private static String property(LlmProvider provider, String field) {
        return PROPERTY_PREFIX + provider.id() + "." + field;
    }
}
