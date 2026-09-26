package org.tyler.model.chat;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One chat message: its speaker and content.
 *
 * <p>Used for history persistence; frontend rendering IDs are not stored.
 * Roles are {@code "user"} and {@code "assistant"}, matching frontend {@code Message.role}.
 */
public record ChatMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") String content) {
}