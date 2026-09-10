package org.tyler.service;

import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.Test;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对 {@link OpenAIClientFactory} 的缓存 / 重建 / 空 key 路径做单元测试。
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