package org.tyler.model.userInfo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 基本信息：姓名 / 性别 / 年龄 / 职业类型。
 */
public record User(
        @JsonProperty("Name") String name,
        @JsonProperty("Gender") String gender,
        @JsonProperty("Age") BigDecimal age,
        @JsonProperty("JobType") String jobType) {
}
