package org.tyler.controller.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tyler.model.chat.ChatMessage;
import org.tyler.service.agent.IAgentService;
import org.tyler.service.chatHistory.IChatHistoryService;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController implements IAgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final IAgentService agentService;
    private final IChatHistoryService chatHistoryService;

    public AgentController(IAgentService agentService, IChatHistoryService chatHistoryService) {
        this.agentService = agentService;
        this.chatHistoryService = chatHistoryService;
    }

    @Override
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        if (request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        String message = request.message().trim();
        log.info("Received chat request with {} characters", message.length());
        log.debug("User message: {}", message);

        long start = System.currentTimeMillis();
        String reply = agentService.ask(message);
        long elapsed = System.currentTimeMillis() - start;

        log.info("Chat completed: reply length {} characters, elapsed {} ms", reply.length(), elapsed);
        log.debug("Reply: {}", reply);
        return new ChatResponse(reply);
    }

    @Override
    @GetMapping("/history")
    public List<ChatMessage> history() {
        return chatHistoryService.get();
    }

    @Override
    @DeleteMapping("/history")
    public List<ChatMessage> clearHistory() {
        return chatHistoryService.clear();
    }
}
