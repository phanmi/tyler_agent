package org.tyler.service.chatHistory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.model.chat.ChatMessage;
import org.tyler.service.userInfo.UserInfoService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Service for reading and saving chat history.
 *
 * <p>Handles JSON serialization, history limits, and role normalization.
 * File access is delegated to {@link IFileSandboxRead} and {@link IFileSandboxWrite},
 * following the same approach as {@link UserInfoService}.
 */
@Service
public class ChatHistoryService implements IChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private static final Set<String> VALID_ROLES = Set.of("user", "assistant");

    // Reuse a thread-safe ObjectMapper, as in UserInfoService.
    // It handles JSON conversion; FileSandbox handles storage.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final String relativePath;

    /**
     * Maximum retained message count, limiting conversation context growth.
     * TODO: Allow runtime changes through configuration refresh or an administration endpoint.
     * Currently injected once at startup through {@code chat.max-messages}.
     */
    private final int maxMessages;

    public ChatHistoryService(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${chat.history-file-path:chat-history.json}") String relativePath,
            @Value("${chat.max-messages:20}") int maxMessages) {
        this.reader = reader;
        this.writer = writer;
        // A relative path within the FileSandbox root.
        this.relativePath = relativePath;
        this.maxMessages = maxMessages;
    }

    @Override
    public List<ChatMessage> get() {
        // Check existence before reading to avoid an error for a missing file.
        if (!reader.exists(relativePath)) {
            return List.of();
        }
        try {
            List<ChatMessage> history = objectMapper.readValue(
                    reader.read(relativePath),
                    new TypeReference<List<ChatMessage>>() {});
            return normalize(history);
        } catch (Exception e) {
            // Return empty history if the file is unreadable or contains invalid JSON.
            log.warn("Failed to read chat history; returning empty history: {}", relativePath, e);
            return List.of();
        }
    }

    @Override
    public List<ChatMessage> append(String role, String content) {
        if (!VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException("role must be user or assistant");
        }
        List<ChatMessage> history = new ArrayList<>(get());
        history.add(new ChatMessage(role, content));
        List<ChatMessage> normalized = normalize(history);
        write(normalized);
        return normalized;
    }

    @Override
    public List<ChatMessage> appendExchange(String userMessage, String assistantReply) {
        // Read, append a complete user/assistant exchange, normalize, and write once.
        // This avoids leaving an unanswered user message if separate writes were to fail.
        List<ChatMessage> history = new ArrayList<>(get());
        history.add(new ChatMessage("user", userMessage));
        history.add(new ChatMessage("assistant", assistantReply));
        List<ChatMessage> normalized = normalize(history);
        write(normalized);
        return normalized;
    }

    @Override
    public List<ChatMessage> clear() {
        write(List.of());
        return List.of();
    }

    /**
     * Skips invalid entries and retains at most {@link #maxMessages} recent messages.
     * Removes leading assistant messages so history starts with a user message.
     */
    private List<ChatMessage> normalize(List<ChatMessage> history) {
        List<ChatMessage> result = new ArrayList<>();
        if (history == null) {
            return result;
        }
        for (ChatMessage msg : history) {
            if (msg == null || msg.role() == null || msg.content() == null) {
                continue;
            }
            if (!VALID_ROLES.contains(msg.role())) {
                continue;
            }
            result.add(new ChatMessage(msg.role(), msg.content()));
        }
        if (result.size() > maxMessages) {
            result = new ArrayList<>(result.subList(result.size() - maxMessages, result.size()));
        }
        // After truncation, discard leading assistant messages to retain complete exchanges.
        while (!result.isEmpty() && !"user".equals(result.get(0).role())) {
            result.remove(0);
        }
        return result;
    }

    private void write(List<ChatMessage> history) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(history);
            writer.write(relativePath, json);
            log.debug("Saved {} chat messages to {}", history.size(), relativePath);
        } catch (IOException | FileWriteException e) {
            log.error("Failed to write chat history: {}", relativePath, e);
            // GenericExceptionHandler maps IllegalStateException to HTTP 500.
            throw new IllegalStateException("Failed to save chat history. Please try again later", e);
        }
    }
}
