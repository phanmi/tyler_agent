package org.tyler.model.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests provider names, defaults, and the model-ID rule enforced at construction. */
class LlmSettingsTest {

    static Map<LlmProvider, String> models(String openai, String anthropic, String deepseek) {
        Map<LlmProvider, String> models = new HashMap<>();
        models.put(LlmProvider.OPENAI, openai);
        models.put(LlmProvider.ANTHROPIC, anthropic);
        models.put(LlmProvider.DEEPSEEK, deepseek);
        return models;
    }

    @Test
    void defaultsAreOpenAiAndEachProvidersDefaultModel() {
        LlmSettings settings = LlmSettings.defaults();

        assertEquals(LlmProvider.OPENAI, settings.activeProvider());
        assertEquals("gpt-6-sol", settings.model(LlmProvider.OPENAI));
        assertEquals("claude-sonnet-5-5", settings.model(LlmProvider.ANTHROPIC));
        assertEquals("deepseek-flash", settings.model(LlmProvider.DEEPSEEK));
    }

    @Test
    void everySuggestionStartsWithTheDefaultAndPassesTheRule() {
        for (LlmProvider provider : LlmProvider.values()) {
            assertEquals(provider.defaultModel(), provider.suggestedModels().getFirst());
            provider.suggestedModels().forEach(model -> assertDoesNotThrow(() -> LlmSettings.requireModelId(model)));
        }
    }

    @Test
    void providerIdsAreExactAndUnknownNamesAreNotEchoed() {
        assertEquals(LlmProvider.ANTHROPIC, LlmProvider.fromId("anthropic"));
        for (String id : new String[]{null, "", "OpenAI", " openai", "<script>", "claude"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> LlmProvider.fromId(id));
            assertEquals("Unknown provider", error.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "gpt-6-sol", "claude-sonnet-5-5", "ft:gpt-6:org:name:id", "org/model_v1.2",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void acceptsModelIdsWithinTheRule(String model) {
        assertDoesNotThrow(() -> LlmSettings.requireModelId(model));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "gpt 6", "gpt-6\n", "model;rm", "../x\\y", "modèl", "a\u0000b",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void rejectsModelIdsOutsideTheRuleWithoutEchoingThem(String model) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> new LlmSettings(LlmProvider.OPENAI, models(model, "claude-sonnet-5-5", "deepseek-flash")));
        assertEquals(LlmSettings.INVALID_MODEL_MESSAGE, error.getMessage());
    }

    @Test
    void requiresTheActiveProviderAndEveryModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmSettings(null, models("a", "b", "c")));
        assertThrows(IllegalArgumentException.class, () -> new LlmSettings(LlmProvider.OPENAI, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LlmSettings(LlmProvider.OPENAI, models("a", null, "c")));
        assertThrows(IllegalArgumentException.class, () -> LlmSettings.requireModelId(null));
    }

    @Test
    void modelsAreCopiedAndUnmodifiable() {
        Map<LlmProvider, String> source = new EnumMap<>(models("a", "b", "c"));
        LlmSettings settings = new LlmSettings(LlmProvider.DEEPSEEK, source);
        source.put(LlmProvider.OPENAI, "changed");

        assertEquals("a", settings.model(LlmProvider.OPENAI));
        assertThrows(UnsupportedOperationException.class, () -> settings.models().put(LlmProvider.OPENAI, "x"));
        assertFalse(settings.models().containsValue("changed"));
    }
}
