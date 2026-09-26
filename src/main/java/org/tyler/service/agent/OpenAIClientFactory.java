package org.tyler.service.agent;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.service.apiKey.IApiKeyService;

/**
 * Creates and caches {@link OpenAIClient} instances through {@link IClientFactory}.
 *
 * <p>Reuses the client while the saved API key is unchanged;
 * creates a new client on first use or after the key changes.
 * A missing key raises {@link OpenAIKeyException} only when a client is requested.
 */
@Service
public class OpenAIClientFactory implements IClientFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenAIClientFactory.class);

    private final IApiKeyService apiKeyService;

    /** API key used by the cached client. */
    private String cachedKey;

    /** Cached client, or null before first use. */
    private OpenAIClient cachedClient;

    public OpenAIClientFactory(IApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public synchronized OpenAIClient getClient() {
        String key = apiKeyService.get();
        if (key == null || key.isBlank()) {
            throw new OpenAIKeyException("OpenAI API key is empty; chat is unavailable");
        }
        // Reuse the client while its API key remains unchanged.
        if (cachedClient != null && key.equals(cachedKey)) {
            return cachedClient;
        }
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(key)
                .build();
        cachedKey = key;
        cachedClient = client;
        log.debug("Created a new OpenAI client");
        return client;
    }
}
