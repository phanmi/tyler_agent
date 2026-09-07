package org.tyler.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.service.IAgentService;

@RestController
@RequestMapping("/api/agent")
public class AgentController implements IAgentController {

    private final IAgentService agentService;

    public AgentController(IAgentService agentService) {
        this.agentService = agentService;
    }

    @Override
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        return new ChatResponse(agentService.ask(request.message().trim()));
    }
}
