package org.tyler.model.userInfo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 补充信息：备注 + 对 LLM 的期望。
 */
public record Other(
        @JsonProperty("Info") String info,
        @JsonProperty("expectationFromLLM") String expectationFromLLM) {
}
