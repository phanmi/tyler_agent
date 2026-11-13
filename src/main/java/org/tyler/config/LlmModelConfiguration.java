package org.tyler.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.tyler.model.llm.LlmModelCatalog;
import org.tyler.model.llm.LlmModelCatalog.ProviderModels;
import org.tyler.model.llm.LlmProvider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Loads each provider's models from {@code llm-models.properties} into an {@link LlmModelCatalog}.
 *
 * <p>The file is shared with the eval harness, so it lives apart from {@code application.yml}.
 * A provider with no entries, or entries the catalog rejects, stops the backend at startup.
 */
@Configuration
@PropertySource("classpath:" + LlmModelConfiguration.MODELS_FILE)
public class LlmModelConfiguration {

    public static final String MODELS_FILE = "llm-models.properties";

    @Bean
    public LlmModelCatalog llmModelCatalog(Environment environment) {
        return read(Binder.get(environment));
    }

    /** Reads every provider's {@code llm.models.<id>.*} properties; the catalog checks them. */
    static LlmModelCatalog read(Binder binder) {
        Map<LlmProvider, ProviderModels> providers = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            String prefix = LlmModelCatalog.PROPERTY_PREFIX + provider.id() + ".";
            providers.put(provider, new ProviderModels(
                    binder.bind(prefix + "default", String.class).orElse(null),
                    binder.bind(prefix + "suggested", Bindable.listOf(String.class)).orElse(List.of()),
                    binder.bind(prefix + "cheap", String.class).orElse(null)));
        }
        return new LlmModelCatalog(providers);
    }
}
