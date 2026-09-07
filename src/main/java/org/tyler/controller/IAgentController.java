package org.tyler.controller;

/**
 * Agent 相关的 REST 契约。
 */
public interface IAgentController {

    ChatResponse chat(ChatRequest request);

    record ChatRequest(String message) {}

    record ChatResponse(String reply) {}
}