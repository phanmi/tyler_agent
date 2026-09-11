package org.tyler.model.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一条聊天消息的最小单元：谁说的 + 说了什么。
 *
 * <p>只用于「历史持久化」；前端本地的 id 是渲染概念，不在这里落盘。
 * role 取值约定为 {@code "user"} / {@code "assistant"}，与前端 {@code Message.role} 对齐。
 */
public record ChatMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content) {
}