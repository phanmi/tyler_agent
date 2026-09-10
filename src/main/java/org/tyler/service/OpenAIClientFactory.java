package org.tyler.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;

/**
 * {@link IClientFactory} 的实现：按需创建并缓存 {@link OpenAIClient}。
 *
 * <p>缓存规则：只要当前保存的 API Key 没有变化，就复用同一个 client；
 * Key 一旦变化（或首次使用），就重建一个新 client。
 * 空 Key 时抛出 {@link OpenAIKeyException}，把「Spring 启动」与「OpenAI 可用」彻底解耦。
 */
@Service
public class OpenAIClientFactory implements IClientFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenAIClientFactory.class);

    private final IApiKeyService apiKeyService;

    /** 当前缓存 client 所用的 Key，用于判断是否需要重建。 */
    private String cachedKey;

    /** 当前缓存的 client；为 null 表示尚未创建。 */
    private OpenAIClient cachedClient;

    public OpenAIClientFactory(IApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public synchronized OpenAIClient getClient() {
        String key = apiKeyService.get();
        if (key == null || key.isBlank()) {
            throw new OpenAIKeyException("OpenAI Key 是空的，chat 不可用");
        }
        // 命中缓存：Key 没变，直接复用，避免每次请求都重建 client。
        if (cachedClient != null && key.equals(cachedKey)) {
            return cachedClient;
        }
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(key)
                .build();
        cachedKey = key;
        cachedClient = client;
        log.debug("已创建新的 OpenAI client");
        return client;
    }
}