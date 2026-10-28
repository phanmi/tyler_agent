package org.tyler.service.chatHistory;

import org.tyler.model.chat.ChatMessage;

import java.util.List;

/**
 * Service contract for chat history.
 *
 * <p>The backend reads saved history before each OpenAI request to provide context.
 * Frontend {@code messages} are a display copy. Operations:
 * <ul>
 *   <li>{@link #get()} returns limited history starting with a user message, or an empty list if unavailable.</li>
 *   <li>{@link #append(String, String)} appends and saves a message, returning the resulting history.</li>
 *   <li>{@link #appendExchange(String, String)} saves a complete user/assistant exchange in one write.</li>
 *   <li>{@link #clear()} clears history and returns an empty list.</li>
 * </ul>
 */
public interface IChatHistoryService {

    /**
     * Returns at most the configured {@code chat.max-messages} recent messages (default 20),
     * starting with a user message, or an empty list if the file is missing or invalid.
     */
    List<ChatMessage> get();

    /**
     * Appends a user or assistant message and returns the resulting history. Fails with
     * {@link IllegalStateException}, leaving the file unchanged, if existing history cannot
     * be read; invalid JSON is replaced.
     */
    List<ChatMessage> append(String role, String content);

    /** Like {@link #append}, but saves a complete user/assistant exchange in one write. */
    List<ChatMessage> appendExchange(String userMessage, String assistantReply);

    /** Clears history and returns an empty list. */
    List<ChatMessage> clear();
}
