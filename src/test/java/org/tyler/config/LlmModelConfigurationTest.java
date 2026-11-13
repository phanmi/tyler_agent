package org.tyler.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tyler.model.llm.LlmModelCatalog;
import org.tyler.model.llm.LlmProvider;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests loading {@code llm-models.properties}: the shipped file, comma lists, and bad entries. */
class LlmModelConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LlmModelConfiguration.class);

    @Test
    void theShippedFileBecomesTheCatalogBean() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(TestLlmModels.CATALOG, context.getBean(LlmModelCatalog.class));
        });
    }

    @Test
    void aCommaSeparatedListKeepsItsOrder() {
        Properties properties = TestLlmModels.shippedProperties();
        properties.setProperty("llm.models.deepseek.suggested", "deepseek-flash,deepseek-x,deepseek-y");

        assertEquals(List.of("deepseek-flash", "deepseek-x", "deepseek-y"),
                TestLlmModels.read(properties).suggestedModels(LlmProvider.DEEPSEEK));
    }

    @Test
    void aBadEntryStopsStartupAndNamesTheProperty() {
        runner.withPropertyValues("llm.models.openai.cheap=gpt-unlisted").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure);
            assertTrue(rootMessage(failure).startsWith("llm.models.openai.cheap must be one of"));
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "llm.models.deepseek.suggested | llm.models.deepseek.suggested must list at least one model",
            "llm.models.anthropic.default | llm.models.anthropic.default must be the first of llm.models.anthropic.suggested",
            "llm.models.openai.cheap | llm.models.openai.cheap must be one of llm.models.openai.suggested"})
    void aMissingEntryIsNamed(String removed, String message) {
        Properties properties = TestLlmModels.shippedProperties();
        properties.remove(removed);

        var error = assertThrows(IllegalArgumentException.class, () -> TestLlmModels.read(properties));
        assertEquals(message, error.getMessage());
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
