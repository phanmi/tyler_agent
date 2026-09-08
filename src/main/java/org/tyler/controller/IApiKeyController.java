package org.tyler.controller;

/**
 * OpenAI API Key 相关的 REST 契约。
 */
public interface IApiKeyController {

    /** 查询 key 是否已配置（只返回布尔，不回传 key 本身）。 */
    StatusResponse status();

    /** 保存 key，返回保存后的配置状态。 */
    StatusResponse save(SaveRequest request);

    record StatusResponse(boolean configured) {}

    record SaveRequest(String apiKey) {}
}