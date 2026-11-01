package org.tyler.model.llm;

import java.util.List;

/**
 * A model provider the Python worker can run, with its default and suggested models.
 *
 * <p>{@link #id()} is the name used in REST paths, the settings file, and the worker
 * protocol's {@code run.provider} field.
 */
public enum LlmProvider {
    OPENAI("openai", "OpenAI", "gpt-6-sol", List.of("gpt-6-sol", "gpt-6.1-sol", "gpt-5.6")),
    ANTHROPIC("anthropic", "Claude", "claude-sonnet-5-5",
            List.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5")),
    DEEPSEEK("deepseek", "DeepSeek", "deepseek-flash", List.of("deepseek-flash", "deepseek-v4-pro"));

    private final String id;
    private final String displayName;
    private final String defaultModel;
    private final List<String> suggestedModels;

    LlmProvider(String id, String displayName, String defaultModel, List<String> suggestedModels) {
        this.id = id;
        this.displayName = displayName;
        this.defaultModel = defaultModel;
        this.suggestedModels = suggestedModels;
    }

    public String id() {
        return id;
    }

    /** The name shown to users, e.g. in error messages. */
    public String displayName() {
        return displayName;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** Suggestions for the Settings picker, default first; any valid model ID is also accepted. */
    public List<String> suggestedModels() {
        return suggestedModels;
    }

    /**
     * Resolves a provider by its exact lowercase id.
     *
     * @throws IllegalArgumentException for a null or unknown id; the message never echoes it
     */
    public static LlmProvider fromId(String id) {
        for (LlmProvider provider : values()) {
            if (provider.id.equals(id)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("Unknown provider");
    }
}
