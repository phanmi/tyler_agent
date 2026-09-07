package org.tyler.service;

/**
 * Agent 服务契约：接收用户消息，返回最终回复文本。
 */
public interface IAgentService {

    String ask(String message);
}