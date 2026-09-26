package org.tyler.service.agent;

import org.junit.jupiter.api.Test;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.service.chatHistory.IChatHistoryService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests cooperation between {@link AgentService} and {@link IClientFactory}.
 * Client lifecycle belongs to the factory; these tests verify delegation and exception propagation.
 */
class AgentServiceTest {

    @Test
    void askDelegatesToClientFactory() {
        IClientFactory clientFactory = mock(IClientFactory.class);
        when(clientFactory.getClient()).thenThrow(new OpenAIKeyException("OpenAI API key is empty; chat is unavailable"));

        AgentService service = new AgentService(clientFactory, "gpt-5.6", List.of(), mock(IChatHistoryService.class));

        assertThrows(OpenAIKeyException.class, () -> service.ask("hello"));
        verify(clientFactory).getClient();
    }

    @Test
    void askPropagatesFactoryException() {
        IClientFactory clientFactory = mock(IClientFactory.class);
        OpenAIKeyException expected = new OpenAIKeyException("OpenAI API key is empty; chat is unavailable");
        when(clientFactory.getClient()).thenThrow(expected);

        AgentService service = new AgentService(clientFactory, "gpt-5.6", List.of(), mock(IChatHistoryService.class));

        OpenAIKeyException actual = assertThrows(OpenAIKeyException.class, () -> service.ask("hello"));
        assertSame(expected, actual);
    }
}
