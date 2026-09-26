package org.tyler.service.chatHistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.model.chat.ChatMessage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests chat history persistence in {@link ChatHistoryService}.
 *
 * <p>Uses a real {@link FileSandbox} under {@code @TempDir}
 * to cover missing files, invalid JSON, and removal of leading assistant messages
 * with actual persisted data.
 */
class ChatHistoryServiceTest {

    private static final String FILE = "chat-history.json";

    @TempDir
    Path tempDir;

    private ChatHistoryService newService(int maxMessages) throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        return new ChatHistoryService(sandbox, sandbox, FILE, maxMessages);
    }

    @Test
    void returnsEmptyWhenFileMissing() throws IOException {
        ChatHistoryService service = newService(20);

        assertTrue(service.get().isEmpty());
    }

    @Test
    void appendExchangeThenReadBack() throws IOException {
        ChatHistoryService service = newService(20);

        List<ChatMessage> saved = service.appendExchange("hello", "world");

        assertEquals(2, saved.size());
        assertEquals("user", saved.get(0).role());
        assertEquals("hello", saved.get(0).content());
        assertEquals("assistant", saved.get(1).role());
        assertEquals("world", saved.get(1).content());

        // Read again to verify the saved content is loaded from disk.
        List<ChatMessage> reloaded = service.get();
        assertEquals(2, reloaded.size());
        assertEquals("hello", reloaded.get(0).content());
        assertEquals("world", reloaded.get(1).content());
    }

    @Test
    void keepsAtMostMaxMessages() throws IOException {
        ChatHistoryService service = newService(20);

        // Eleven exchanges produce 22 messages; retain the latest 20.
        for (int i = 1; i <= 11; i++) {
            service.appendExchange("q" + i, "a" + i);
        }

        List<ChatMessage> history = service.get();
        assertEquals(20, history.size());
        // The first exchange is discarded, so user2 becomes the first message.
        assertEquals("user", history.get(0).role());
        assertEquals("q2", history.get(0).content());
    }

    @Test
    void historyStartsWithUser() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        ChatHistoryService service = new ChatHistoryService(sandbox, sandbox, FILE, 20);
        // Write history starting with an assistant to test removal of leading assistant entries.
        sandbox.write(FILE, "[{\"role\":\"assistant\",\"content\":\"a\"},"
                + "{\"role\":\"user\",\"content\":\"q\"},"
                + "{\"role\":\"assistant\",\"content\":\"a2\"}]");

        List<ChatMessage> history = service.get();

        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("q", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void corruptedJsonReturnsEmpty() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        ChatHistoryService service = new ChatHistoryService(sandbox, sandbox, FILE, 20);
        sandbox.write(FILE, "not a valid json {{{");

        assertTrue(service.get().isEmpty());
    }

    @Test
    void clearReturnsEmptyArray() throws IOException {
        ChatHistoryService service = newService(20);
        service.appendExchange("hello", "world");

        List<ChatMessage> cleared = service.clear();

        assertTrue(cleared.isEmpty());
        assertTrue(service.get().isEmpty());
    }

    @Test
    void maxMessagesIsConfigurable() throws IOException {
        // A limit of four messages retains two of three exchanges.
        ChatHistoryService service = newService(4);
        for (int i = 1; i <= 3; i++) {
            service.appendExchange("q" + i, "a" + i);
        }

        List<ChatMessage> history = service.get();

        assertEquals(4, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("q2", history.get(0).content());
    }
}
