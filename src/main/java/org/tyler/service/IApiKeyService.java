package org.tyler.service;

/**
 * OpenAI API Key 的读写契约。
 *
 * <p>Key 以纯文本形式存在沙盒文件里，通过 {@code isConfigured} / {@code get} / {@code save}
 * 三个方法管理；本契约不暴露文件路径等 IO 细节。
 */
public interface IApiKeyService {

    /** 是否已配置一个非空的 API Key。 */
    boolean isConfigured();

    /** 读取当前保存的 API Key；文件不存在时返回空串。 */
    String get();

    /** 保存 API Key（trim 后写盘）；传空串表示清空。 */
    void save(String apiKey);
}