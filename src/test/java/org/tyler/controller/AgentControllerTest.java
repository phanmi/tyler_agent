package org.tyler.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.service.IAgentService;
import org.tyler.service.IChatHistoryService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerTest {

    private MockMvc mockMvc;
    private IAgentService agentService;
    private IChatHistoryService chatHistoryService;

    @BeforeEach
    void setUp() {
        agentService = mock(IAgentService.class);
        chatHistoryService = mock(IChatHistoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentController(agentService, chatHistoryService))
                .setControllerAdvice(new GenericExceptionHandler())
                .build();
    }

    @Test
    void chatReturnsReply() throws Exception {
        when(agentService.ask("hello")).thenReturn("hi there");

        mockMvc.perform(post("/api/agent/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("hi there"));
    }

    @Test
    void blankMessageIsRejected() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }
}
