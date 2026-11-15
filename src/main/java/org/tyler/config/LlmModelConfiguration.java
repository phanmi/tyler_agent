package org.tyler.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.tyler.model.llm.LlmModelCatalog;
import org.tyler.model.llm.LlmModelCatalog.ProviderModels;
import org.tyler.model.llm.LlmProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Loads each provider's models from {@code llm-models.properties} into an {@link LlmModelCatalog}.
 *
 * <p>The file is shared with the eval harness, which reads it directly, so it lives apart from
 * {@code application.yml}. The catalog is built from the file's own entries and nothing else:
 * a system property, an environment variable, or an {@code application.yml} entry cannot
 * replace one, because Settings and the evals would then offer different models. A provider
 * with no entries, or entries the catalog rejects, stops the backend at startup.
 */
@Configuration
public class LlmModelConfiguration {

    public static final String MODELS_FILE = "llm-models.properties";

    @Bean
    public LlmModelCatalog llmModelCatalog() {
        return read(load(new ClassPathResource(MODELS_FILE)));
    }

    /**
     * The file's entries, read as UTF-8, which is how the eval harness reads it.
     *
     * @throws UncheckedIOException naming the file when it is missing or cannot be read
     */
    static Properties load(Resource file) {
        try {
            return PropertiesLoaderUtils.loadProperties(new EncodedResource(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file.getDescription(), e);
        }
    }

    /** Reads every provider's {@code llm.models.<id>.*} entries; the catalog checks them. */
    static LlmModelCatalog read(Properties entries) {
        Binder binder = new Binder(new MapConfigurationPropertySource(entries));
        Map<LlmProvider, ProviderModels> providers = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            String prefix = LlmModelCatalog.PROPERTY_PREFIX + provider.id() + ".";
            providers.put(provider, new ProviderModels(
                    binder.bind(prefix + "default", String.class).orElse(null),
                    binder.bind(prefix + "suggested", Bindable.listOf(String.class)).orElse(List.of()),
                    binder.bind(prefix + "cheap", String.class).orElse(null),
                    binder.bind(prefix + "label", Bindable.mapOf(String.class, String.class)).orElse(Map.of())));
        }
        return new LlmModelCatalog(providers);
    }
}
