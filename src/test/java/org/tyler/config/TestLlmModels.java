package org.tyler.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.tyler.model.llm.LlmModelCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The model catalog for tests, read from the shipped {@code llm-models.properties} the way the
 * backend reads it, so tests follow the file instead of repeating its model IDs.
 */
public final class TestLlmModels {

    public static final LlmModelCatalog CATALOG = read(shippedProperties());

    private TestLlmModels() {
    }

    /** A fresh copy of the shipped file's properties, which a test may change. */
    public static Properties shippedProperties() {
        try {
            return PropertiesLoaderUtils.loadProperties(new ClassPathResource(LlmModelConfiguration.MODELS_FILE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads properties into a catalog through the backend's own binding. */
    public static LlmModelCatalog read(Properties properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("models", properties));
        return LlmModelConfiguration.read(Binder.get(environment));
    }
}
