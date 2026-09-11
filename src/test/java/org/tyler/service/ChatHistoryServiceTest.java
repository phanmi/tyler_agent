package org.tyler.service;

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
 * 对 {@link ChatHistoryService} 的核心读写语义做单元测试。
 *
 * <p>不 mock {@code IFileSandboxRead/Write}，而是注入真实的 {@link FileSandbox}（落在
 * {@code @TempDir}），这样能真实覆盖「文件不存在 / 损坏 JSON / 从 user 开头」等依赖
 * 真实文件内容的场景，而不是靠 stub 假装磁盘行为。
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

        // 重新读取：应从磁盘读回同样的内容，而非只返回内存里的那份。
        List<ChatMessage> reloaded = service.get();
        assertEquals(2, reloaded.size());
        assertEquals("hello", reloaded.get(0).content());
        assertEquals("world", reloaded.get(1).content());
    }

    @Test
    void keepsAtMostMaxMessages() throws IOException {
        ChatHistoryService service = newService(20);

        // 追加 11 轮 = 22 条，应截断到最近 20 条（最旧的一轮被丢弃）。
        for (int i = 1; i <= 11; i++) {
            service.appendExchange("q" + i, "a" + i);
        }

        List<ChatMessage> history = service.get();
        assertEquals(20, history.size());
        // 最旧的 user1/assistant1 被截掉，第一条应是 user2。
        assertEquals("user", history.get(0).role());
        assertEquals("q2", history.get(0).content());
    }

    @Test
    void historyStartsWithUser() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        ChatHistoryService service = new ChatHistoryService(sandbox, sandbox, FILE, 20);
        // 手写一个开头是 assistant 的历史，验证 get() 会把开头的 assistant 剥掉。
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
        // 注入 maxMessages=4：追加 3 轮 = 6 条，应截断到 4 条，证明该值可配置生效。
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
