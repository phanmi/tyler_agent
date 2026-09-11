package org.tyler.service;

import org.tyler.model.chat.ChatMessage;

import java.util.List;

/**
 * 聊天历史相关的服务契约。
 *
 * <p>历史是「跨轮记忆」的唯一真相来源：后端每次调用 OpenAI 前从这里读历史并重发，
 * 前端 {@code messages} 只是展示副本。读写语义：
 * <ul>
 *   <li>{@link #get()} 返回截断后、从 user 开头、角色交替的历史（文件不存在/损坏返回空列表）。</li>
 *   <li>{@link #append(String, String)} 追加一条消息并写回，返回追加后的完整历史。</li>
 *   <li>{@link #appendExchange(String, String)} 一次性追加完整一轮（user + assistant）并写回，返回追加后的完整历史。</li>
 *   <li>{@link #clear()} 清空历史，返回空列表。</li>
 * </ul>
 */
public interface IChatHistoryService {

    /** 读取当前保存的历史；文件不存在或损坏时返回空列表。 */
    List<ChatMessage> get();

    /** 追加一条消息（role 只能是 user / assistant），返回追加后的完整历史。 */
    List<ChatMessage> append(String role, String content);

    /** 一次性追加完整一轮对话（user + assistant），返回追加后的完整历史。 */
    List<ChatMessage> appendExchange(String userMessage, String assistantReply);

    /** 清空历史，返回空列表。 */
    List<ChatMessage> clear();
}