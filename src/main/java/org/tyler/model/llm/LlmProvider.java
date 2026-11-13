package org.tyler.model.llm;

/**
 * A model provider the Python worker can run.
 *
 * <p>{@link #id()} is the name used in REST paths, the settings file, the worker protocol's
 * {@code run.provider} field, and {@code llm-models.properties}, which lists each provider's
 * models (see {@link LlmModelCatalog}).
 */
public enum LlmProvider {
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Claude"),
    DEEPSEEK("deepseek", "DeepSeek");

    private final String id;
    private final String displayName;

    LlmProvider(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    /** The name shown to users, e.g. in error messages. */
    public String displayName() {
        return displayName;
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
