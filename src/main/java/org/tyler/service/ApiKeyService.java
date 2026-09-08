package org.tyler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;

/**
 * OpenAI API Key 读写服务的实现。
 *
 * <p>Key 保存在沙盒内一个纯文本文件里，所有 IO 都通过注入的
 * {@link IFileSandboxRead} / {@link IFileSandboxWrite} 完成，本类不直接触碰磁盘。
 */
@Service
public class ApiKeyService implements IApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final String relativePath;

    public ApiKeyService(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${openai.key-file-path:apikey.txt}") String relativePath) {
        this.reader = reader;
        this.writer = writer;
        // 沙箱内的相对路径，实际位置由 FileSandbox 的根目录决定。
        this.relativePath = relativePath;
    }

    @Override
    public boolean isConfigured() {
        return !get().isBlank();
    }

    @Override
    public String get() {
        // 先判存在，避免 reader.read() 对「不存在」抛 FileReadException。
        if (!reader.exists(relativePath)) {
            return "";
        }
        String key = reader.read(relativePath);
        return key == null ? "" : key.trim();
    }

    @Override
    public void save(String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        writer.write(relativePath, normalized);
        log.info("OpenAI API Key 已保存到 {}", relativePath);
    }
}