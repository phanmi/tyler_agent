package org.tyler.service.agent;

import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.service.apiKey.IApiKeyService;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests client caching, rebuilding, and missing-key handling in {@link OpenAIClientFactory}.
 */
class OpenAIClientFactoryTest {

    @Test
    void emptyKeyThrowsOpenAIKeyException() {
        IApiKeyService apiKeyService = mock(IApiKeyService.class);
        when(apiKeyService.get()).thenReturn("");

        OpenAIClientFactory factory = new OpenAIClientFactory(apiKeyService);

        assertThrows(OpenAIKeyException.class, factory::getClient);
    }

    @Test
    void sameKeyReturnsCachedClient() {
        IApiKeyService apiKeyService = mock(IApiKeyService.class);
        when(apiKeyService.get()).thenReturn("sk-abc123");

        OpenAIClientFactory factory = new OpenAIClientFactory(apiKeyService);

        OpenAIClient first = factory.getClient();
        OpenAIClient second = factory.getClient();

        assertSame(first, second);
    }

    @Test
    void changedKeyRebuildsClient() {
        IApiKeyService apiKeyService = mock(IApiKeyService.class);
        when(apiKeyService.get()).thenReturn("sk-first");

        OpenAIClientFactory factory = new OpenAIClientFactory(apiKeyService);
        OpenAIClient first = factory.getClient();

        when(apiKeyService.get()).thenReturn("sk-second");
        OpenAIClient second = factory.getClient();

        assertNotSame(first, second);
    }
}
