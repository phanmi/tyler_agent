package org.tyler.service.apiKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;

/**
 * Service for reading and saving the OpenAI API key.
 *
 * <p>The key is stored as plain text in a sandbox file.
 * All file access goes through {@link IFileSandboxRead} and {@link IFileSandboxWrite}.
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
        // A relative path within the FileSandbox root.
        this.relativePath = relativePath;
    }

    @Override
    public boolean isConfigured() {
        return !get().isBlank();
    }

    @Override
    public String get() {
        // Check existence before reading to avoid an error for a missing file.
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
        log.info("Saved the OpenAI API key to {}", relativePath);
    }
}