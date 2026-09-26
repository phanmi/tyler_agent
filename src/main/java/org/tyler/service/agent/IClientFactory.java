package org.tyler.service.agent;

import com.openai.client.OpenAIClient;

/**
 * Factory contract for OpenAI clients.
 *
 * <p>{@link #getClient()} hides caching and rebuilding decisions
 * so callers can obtain a client without managing its lifecycle.
 */
public interface IClientFactory {

    /** Returns a cached {@link OpenAIClient} or creates one when needed. */
    OpenAIClient getClient();
}