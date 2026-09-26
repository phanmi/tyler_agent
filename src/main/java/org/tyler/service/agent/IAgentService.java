package org.tyler.service.agent;

/**
 * Agent service contract: accepts a user message and returns the final reply.
 */
public interface IAgentService {

    String ask(String message);
}