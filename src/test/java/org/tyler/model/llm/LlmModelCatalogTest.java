package org.tyler.model.llm;

import org.junit.jupiter.api.Test;
import org.tyler.model.llm.LlmModelCatalog.ProviderModels;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests the catalog's checks, which run when the backend starts, and the default settings. */
class LlmModelCatalogTest {

    private static Map<LlmProvider, ProviderModels> valid() {
        Map<LlmProvider, ProviderModels> providers = new EnumMap<>(LlmProvider.class);
        providers.put(LlmProvider.OPENAI, new ProviderModels("o-1", List.of("o-1", "o-2"), "o-2"));
        providers.put(LlmProvider.ANTHROPIC, new ProviderModels("a-1", List.of("a-1"), "a-1"));
        providers.put(LlmProvider.DEEPSEEK, new ProviderModels("d-1", List.of("d-1", "d-2"), "d-1"));
        return providers;
    }

    private static String rejection(LlmProvider provider, ProviderModels models) {
        Map<LlmProvider, ProviderModels> providers = valid();
        providers.put(provider, models);
        return assertThrows(IllegalArgumentException.class, () -> new LlmModelCatalog(providers)).getMessage();
    }

    @Test
    void defaultSettingsAreOpenAiAndEachProvidersDefaultModel() {
        LlmSettings settings = new LlmModelCatalog(valid()).defaultSettings();

        assertEquals(LlmProvider.OPENAI, settings.activeProvider());
        assertEquals("o-1", settings.model(LlmProvider.OPENAI));
        assertEquals("a-1", settings.model(LlmProvider.ANTHROPIC));
        assertEquals("d-1", settings.model(LlmProvider.DEEPSEEK));
    }

    @Test
    void suggestionsKeepTheirOrderAndCannotChange() {
        List<String> source = new ArrayList<>(List.of("o-1", "o-2"));
        Map<LlmProvider, ProviderModels> providers = valid();
        providers.put(LlmProvider.OPENAI, new ProviderModels("o-1", source, "o-2"));
        LlmModelCatalog catalog = new LlmModelCatalog(providers);
        source.add("o-3");

        assertEquals(List.of("o-1", "o-2"), catalog.suggestedModels(LlmProvider.OPENAI));
        assertEquals("o-1", catalog.defaultModel(LlmProvider.OPENAI));
        assertThrows(UnsupportedOperationException.class, () -> catalog.suggestedModels(LlmProvider.OPENAI).add("x"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.providers().remove(LlmProvider.OPENAI));
    }

    @Test
    void aProviderWithoutEntriesIsNamed() {
        Map<LlmProvider, ProviderModels> providers = valid();
        providers.remove(LlmProvider.ANTHROPIC);

        var error = assertThrows(IllegalArgumentException.class, () -> new LlmModelCatalog(providers));
        assertEquals("llm.models.anthropic.suggested is missing", error.getMessage());
    }

    @Test
    void eachBrokenRuleNamesItsProperty() {
        assertEquals("llm.models.openai.suggested must list at least one model",
                rejection(LlmProvider.OPENAI, new ProviderModels("o-1", List.of(), "o-1")));
        assertEquals("llm.models.openai.suggested has an invalid model ID 'bad model'",
                rejection(LlmProvider.OPENAI, new ProviderModels("o-1", List.of("o-1", "bad model"), "o-1")));
        assertEquals("llm.models.deepseek.default must be the first of llm.models.deepseek.suggested",
                rejection(LlmProvider.DEEPSEEK, new ProviderModels("d-2", List.of("d-1", "d-2"), "d-1")));
        assertEquals("llm.models.anthropic.cheap must be one of llm.models.anthropic.suggested",
                rejection(LlmProvider.ANTHROPIC, new ProviderModels("a-1", List.of("a-1"), "a-2")));
        assertEquals("llm.models.anthropic.cheap must be one of llm.models.anthropic.suggested",
                rejection(LlmProvider.ANTHROPIC, new ProviderModels("a-1", List.of("a-1"), null)));
    }
}
