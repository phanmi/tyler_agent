package org.tyler.service.agent.python;

import org.tyler.model.chat.ChatMessage;
import org.tyler.service.chatHistory.IChatHistoryService;

import java.util.List;

/** History clear/read/write shares the Python chat lane, preventing older completion from undoing clear. */
public final class CoordinatedChatHistoryService implements IChatHistoryService {
    private final IChatHistoryService delegate;
    private final ConversationLane lane;

    public CoordinatedChatHistoryService(IChatHistoryService delegate, ConversationLane lane) {
        this.delegate = delegate;
        this.lane = lane;
    }

    @Override public List<ChatMessage> get() { return lane.call(delegate::get); }
    @Override public List<ChatMessage> clear() { return lane.call(delegate::clear); }
    @Override public List<ChatMessage> append(String role, String content) {
        return lane.call(() -> delegate.append(role, content));
    }
    @Override public List<ChatMessage> appendExchange(String user, String assistant) {
        return lane.call(() -> delegate.appendExchange(user, assistant));
    }
}
