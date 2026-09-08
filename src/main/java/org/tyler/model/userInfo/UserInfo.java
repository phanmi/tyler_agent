package org.tyler.model.userInfo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 顶层结构：User 基本信息 + Other 补充信息。
 */
public record UserInfo(
        @JsonProperty("User") User user,
        @JsonProperty("Other") Other other) {
}
