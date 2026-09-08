package org.tyler.service;

import org.junit.jupiter.api.Test;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对 {@link AgentService} 的空 key 路径做单元测试：
 * key 为空时应在真正调用 OpenAI 之前就抛出 {@link OpenAIKeyException}。
 */
class AgentServiceTest {

    @Test
    void emptyApiKeyThrowsOpenAIKeyException() {
        IApiKeyService apiKeyService = mock(IApiKeyService.class);
        when(apiKeyService.get()).thenReturn("");

        AgentService service = new AgentService(apiKeyService, "gpt-5.6", List.of());

        assertThrows(OpenAIKeyException.class, () -> service.ask("hello"));
    }
}
