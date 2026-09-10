package org.tyler.service;

import com.openai.client.OpenAIClient;

/**
 * OpenAI client 工厂契约。
 *
 * <p>只暴露 {@link #getClient()} 一个方法：调用方不需要关心 client 是否已经缓存、
 * 何时需要重建，工厂内部负责「有则复用、无则创建」。
 */
public interface IClientFactory {

    /** 返回一个可用的 {@link OpenAIClient}；没有可用实例时创建，有则复用已有实例。 */
    OpenAIClient getClient();
}