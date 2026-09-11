package org.tyler.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.filesandbox.exceptions.FileWriteException;
import org.tyler.model.chat.ChatMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 聊天历史读写服务的实现。
 *
 * <p>把「JSON 序列化 / 截断 / 角色对齐」等业务逻辑收敛到这里，
 * 但所有文件 IO 都通过注入的 {@link IFileSandboxRead} / {@link IFileSandboxWrite} 完成，
 * 本类不直接触碰磁盘——与 {@link UserInfoService} 同一套范式。
 */
@Service
public class ChatHistoryService implements IChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    /** 历史最多保留的消息条数（约 10 轮对话），防止 token 无限膨胀。 */
    private static final int MAX_MESSAGES = 20;

    private static final Set<String> VALID_ROLES = Set.of("user", "assistant");

    // 与 UserInfoService 一致：直接 new 一个 ObjectMapper（线程安全、可复用），
    // 只负责「JSON 字符串 <-> 对象」序列化，真正的落盘/回读交给 FileSandbox。
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final String relativePath;

    public ChatHistoryService(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${chat.history-file-path:chat-history.json}") String relativePath) {
        this.reader = reader;
        this.writer = writer;
        // 沙箱内的相对路径，实际位置由 FileSandbox 的根目录决定。
        this.relativePath = relativePath;
    }

    @Override
    public List<ChatMessage> get() {
        // 先判存在，避免 reader.read() 对「不存在」抛 FileReadException。
        if (!reader.exists(relativePath)) {
            return List.of();
        }
        try {
            List<ChatMessage> history = objectMapper.readValue(
                    reader.read(relativePath),
                    new TypeReference<List<ChatMessage>>() {});
            return normalize(history);
        } catch (Exception e) {
            // 文件被手改坏 / JSON 不合法 / 读取失败时，不抛 500，返回空历史兜底。
            log.warn("读取聊天历史文件失败，返回空历史：{}", relativePath, e);
            return List.of();
        }
    }

    @Override
    public List<ChatMessage> append(String role, String content) {
        if (!VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException("role 只能是 user 或 assistant");
        }
        List<ChatMessage> history = new ArrayList<>(get());
        history.add(new ChatMessage(role, content));
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
     * 清洗 + 截断：跳过脏数据、只保留最近 {@link #MAX_MESSAGES} 条，
     * 并保证列表从 user 开头（去掉开头的 assistant，使角色对齐）。
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
        if (result.size() > MAX_MESSAGES) {
            result = new ArrayList<>(result.subList(result.size() - MAX_MESSAGES, result.size()));
        }
        // 从 user 开头：若截断后第一条是 assistant，去掉它（正常追加是 user→assistant 成对，去掉后仍交替）。
        while (!result.isEmpty() && !"user".equals(result.get(0).role())) {
            result.remove(0);
        }
        return result;
    }

    private void write(List<ChatMessage> history) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(history);
            writer.write(relativePath, json);
            log.debug("聊天历史已写入 {}（{} 条）", relativePath, history.size());
        } catch (IOException | FileWriteException e) {
            log.error("写聊天历史文件失败：{}", relativePath, e);
            // 抛 IllegalStateException 走 GenericExceptionHandler 的兜底，返回 500。
            throw new IllegalStateException("保存聊天历史失败，请稍后重试", e);
        }
    }
}