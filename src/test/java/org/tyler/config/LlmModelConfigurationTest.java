package org.tyler.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.tyler.model.llm.LlmModelCatalog;
import org.tyler.model.llm.LlmProvider;

import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests loading {@code llm-models.properties}: the shipped file, comma lists, labels, bad
 * entries, and that nothing outside the file can change the catalog.
 */
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
    void aSpringPropertyCannotReplaceAnEntryOfTheFile() {
        // The eval harness reads the file itself, so an override would make the two disagree.
        runner.withPropertyValues("llm.models.deepseek.suggested=deepseek-flash,zzz-override")
                .withSystemProperties("llm.models.openai.cheap=gpt-6-sol")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(TestLlmModels.CATALOG, context.getBean(LlmModelCatalog.class));
                });
    }

    @Test
    void aCommaSeparatedListKeepsItsOrder() {
        Properties properties = TestLlmModels.shippedProperties();
        properties.setProperty("llm.models.deepseek.suggested", "deepseek-flash,deepseek-x,deepseek-y");

        assertEquals(List.of("deepseek-flash", "deepseek-x", "deepseek-y"),
                LlmModelConfiguration.read(properties).suggestedModels(LlmProvider.DEEPSEEK));
    }

    @Test
    void aLabelBelongsToTheModelIdInItsBrackets() {
        Properties properties = TestLlmModels.shippedProperties();
        properties.setProperty("llm.models.openai.label[gpt-6.1-sol]", "GPT 6.1 Sol");

        LlmModelCatalog catalog = LlmModelConfiguration.read(properties);

        assertEquals(Map.of("gpt-6.1-sol", "GPT 6.1 Sol"), catalog.modelLabels(LlmProvider.OPENAI));
        assertEquals(Map.of(), catalog.modelLabels(LlmProvider.ANTHROPIC));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "llm.models.openai.cheap | gpt-unlisted | llm.models.openai.cheap must be one of llm.models.openai.suggested",
            "llm.models.openai.label[gpt-unlisted] | Unlisted | llm.models.openai.label[gpt-unlisted] must name one of llm.models.openai.suggested",
            "llm.models.openai.label[gpt-6-luna] | '' | llm.models.openai.label[gpt-6-luna] must not be blank"})
    void aBadEntryIsRefusedAndNamesTheProperty(String property, String value, String message) {
        Properties properties = TestLlmModels.shippedProperties();
        properties.setProperty(property, value);

        var error = assertThrows(IllegalArgumentException.class, () -> LlmModelConfiguration.read(properties));
        assertEquals(message, error.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "llm.models.deepseek.suggested | llm.models.deepseek.suggested must list at least one model",
            "llm.models.anthropic.default | llm.models.anthropic.default must be the first of llm.models.anthropic.suggested",
            "llm.models.openai.cheap | llm.models.openai.cheap must be one of llm.models.openai.suggested"})
    void aMissingEntryIsNamed(String removed, String message) {
        Properties properties = TestLlmModels.shippedProperties();
        properties.remove(removed);

        var error = assertThrows(IllegalArgumentException.class, () -> LlmModelConfiguration.read(properties));
        assertEquals(message, error.getMessage());
    }

    @Test
    void aFileThatCannotBeReadIsNamed() {
        var error = assertThrows(UncheckedIOException.class,
                () -> LlmModelConfiguration.load(new ClassPathResource("no-such-models.properties")));

        assertTrue(error.getMessage().startsWith("Cannot read "));
        assertTrue(error.getMessage().contains("no-such-models.properties"));
        assertInstanceOf(FileNotFoundException.class, error.getCause());
    }
}
