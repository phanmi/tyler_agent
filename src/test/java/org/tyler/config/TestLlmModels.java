package org.tyler.config;

import org.springframework.core.io.ClassPathResource;
import org.tyler.model.llm.LlmModelCatalog;

import java.util.Properties;

/**
 * The model catalog for tests, read from the shipped {@code llm-models.properties} the way the
 * backend reads it, so tests follow the file instead of repeating its model IDs.
 */
public final class TestLlmModels {

    public static final LlmModelCatalog CATALOG = LlmModelConfiguration.read(shippedProperties());

    private TestLlmModels() {
    }

    /** A fresh copy of the shipped file's properties, which a test may change. */
    public static Properties shippedProperties() {
        return LlmModelConfiguration.load(new ClassPathResource(LlmModelConfiguration.MODELS_FILE));
    }
}
