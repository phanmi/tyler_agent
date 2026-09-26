package org.tyler.model.userInfo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Top-level profile combining User details and Other information.
 */
public record UserInfo(
        @JsonProperty("User") User user,
        @JsonProperty("Other") Other other) {
}
