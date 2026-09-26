package org.tyler.model.userInfo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Additional profile information: notes and expectations for the model.
 */
public record Other(
        @JsonProperty("Info") String info,
        @JsonProperty("expectationFromLLM") String expectationFromLLM) {
}
