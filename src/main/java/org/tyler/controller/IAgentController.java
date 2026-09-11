package org.tyler.controller;

import org.tyler.model.chat.ChatMessage;

import java.util.List;

/**
 * Agent 相关的 REST 契约。
 */
public interface IAgentController {

    ChatResponse chat(ChatRequest request);

    List<ChatMessage> history();

    List<ChatMessage> clearHistory();

    record ChatRequest(String message) {}

    record ChatResponse(String reply) {}
}