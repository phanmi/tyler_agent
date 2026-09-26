package org.tyler.controller.agent;

import org.tyler.model.chat.ChatMessage;

import java.util.List;

/**
 * REST contract for agent operations.
 */
public interface IAgentController {

    ChatResponse chat(ChatRequest request);

    List<ChatMessage> history();

    List<ChatMessage> clearHistory();

    record ChatRequest(String message) {}

    record ChatResponse(String reply) {}
}