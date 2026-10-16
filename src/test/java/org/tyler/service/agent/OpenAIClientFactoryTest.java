package org.tyler.service.agent;

import com.openai.client.OpenAIClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.service.apiKey.IApiKeyService;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterizes deferred key lookup, caching, and key rotation without sending API requests.
 */
class OpenAIClientFactoryTest {

    private static final String FIRST_KEY = "test-only-first-key";
    private static final String SECOND_KEY = "test-only-second-key";

    private final IApiKeyService apiKeyService = mock(IApiKeyService.class);
    private final OpenAIClientFactory factory = new OpenAIClientFactory(apiKeyService);
    private final List<OpenAIClient> clients = new ArrayList<>();

    @AfterEach
    void closeClients() {
        clients.forEach(OpenAIClient::close);
    }

    @Test
    void constructionDoesNotReadTheApiKey() {
        verifyNoInteractions(apiKeyService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void missingKeyThrowsOpenAIKeyException(String key) {
        when(apiKeyService.get()).thenReturn(key);

        OpenAIKeyException exception = assertThrowsExactly(OpenAIKeyException.class, factory::getClient);

        assertEquals("OpenAI API key is empty; chat is unavailable", exception.getMessage());
    }

    @Test
    void sameKeyReturnsCachedClient() {
        when(apiKeyService.get()).thenReturn(FIRST_KEY);

        OpenAIClient first = factory.getClient();
        clients.add(first);
        OpenAIClient second = factory.getClient();

        assertSame(first, second);
        verify(apiKeyService, times(2)).get();
    }

    @Test
    void changedKeyRebuildsClientAndCachesTheReplacement() {
        when(apiKeyService.get()).thenReturn(FIRST_KEY);

        OpenAIClient first = factory.getClient();
        clients.add(first);

        when(apiKeyService.get()).thenReturn(SECOND_KEY);
        OpenAIClient second = factory.getClient();
        clients.add(second);

        assertNotSame(first, second);
        assertSame(second, factory.getClient());
        verify(apiKeyService, times(3)).get();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void removingKeyPreventsUseOfTheCachedClient(String removedKey) {
        when(apiKeyService.get()).thenReturn(FIRST_KEY);
        clients.add(factory.getClient());

        when(apiKeyService.get()).thenReturn(removedKey);

        OpenAIKeyException exception = assertThrowsExactly(OpenAIKeyException.class, factory::getClient);

        assertEquals("OpenAI API key is empty; chat is unavailable", exception.getMessage());
    }

    @Test
    void keyReadFailurePropagatesWithoutReturningTheCachedClient() {
        when(apiKeyService.get()).thenReturn(FIRST_KEY);
        clients.add(factory.getClient());
        IllegalStateException failure = new IllegalStateException("Cannot read the saved API key");
        when(apiKeyService.get()).thenThrow(failure);

        IllegalStateException exception = assertThrowsExactly(IllegalStateException.class, factory::getClient);

        assertSame(failure, exception);
    }
}
