package org.tyler.model.userInfo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Basic profile information: name, gender, age, and occupation.
 */
public record User(
        @JsonProperty("Name") String name,
        @JsonProperty("Gender") String gender,
        @JsonProperty("Age") BigDecimal age,
        @JsonProperty("JobType") String jobType) {
}
