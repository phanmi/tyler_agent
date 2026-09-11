package org.tyler.service;

import org.junit.jupiter.api.Test;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对 {@link AgentService} 与 {@link IClientFactory} 的协作做单元测试：
 * client 的创建 / 缓存已下沉到工厂，这里只验证 AgentService 正确委托并透传异常。
 */
class AgentServiceTest {

    @Test
    void askDelegatesToClientFactory() {
        IClientFactory clientFactory = mock(IClientFactory.class);
        when(clientFactory.getClient()).thenThrow(new OpenAIKeyException("OpenAI Key 是空的，chat 不可用"));

        AgentService service = new AgentService(clientFactory, "gpt-5.6", List.of(), mock(IChatHistoryService.class));

        assertThrows(OpenAIKeyException.class, () -> service.ask("hello"));
        verify(clientFactory).getClient();
    }

    @Test
    void askPropagatesFactoryException() {
        IClientFactory clientFactory = mock(IClientFactory.class);
        OpenAIKeyException expected = new OpenAIKeyException("OpenAI Key 是空的，chat 不可用");
        when(clientFactory.getClient()).thenThrow(expected);

        AgentService service = new AgentService(clientFactory, "gpt-5.6", List.of(), mock(IChatHistoryService.class));

        OpenAIKeyException actual = assertThrows(OpenAIKeyException.class, () -> service.ask("hello"));
        assertSame(expected, actual);
    }
}
