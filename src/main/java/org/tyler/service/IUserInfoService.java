package org.tyler.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * 用户信息相关的服务契约。
 *
 * <p>这里同时承载「数据模型」与「业务方法」两部分：
 * 数据模型（UserInfo / User / Other）用 {@link JsonProperty} 把字段名固定为大写开头，
 * 与用户约定的落盘 JSON 结构保持一致；业务方法（get / save）定义读写语义。
 */
public interface IUserInfoService {

    /** 读取当前保存的用户信息；文件不存在或损坏时返回空结构。 */
    UserInfo get();

    /** 保存用户信息，返回保存后的结构。 */
    UserInfo save(UserInfo userInfo);

    /** 顶层结构：User 基本信息 + Other 补充信息。 */
    record UserInfo(
            @JsonProperty("User") User user,
            @JsonProperty("Other") Other other) {
    }

    /** 基本信息：姓名 / 性别 / 年龄 / 职业类型。 */
    record User(
            @JsonProperty("Name") String name,
            @JsonProperty("Gender") String gender,
            @JsonProperty("Age") BigDecimal age,
            @JsonProperty("JobType") String jobType) {
    }

    /** 补充信息：备注 + 对 LLM 的期望。 */
    record Other(
            @JsonProperty("Info") String info,
            @JsonProperty("expectationFromLLM") String expectationFromLLM) {
    }
}
