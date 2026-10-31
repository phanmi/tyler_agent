package org.tyler.service.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.WorkspacePathException;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.llm.LlmProvider;
import org.tyler.model.llm.LlmSettings;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Stores the active provider and each provider's model as JSON in a sandbox file:
 * {@code {"activeProvider":"openai","models":{"openai":"gpt-6-sol",...}}}.
 *
 * <p>A missing file means defaults. An unreadable or invalid file is a read failure rather
 * than a silent fallback, so chats never move to a provider or model the user didn't choose.
 */
@Service
public class LlmSettingsService implements ILlmSettingsService {

    private static final Logger log = LoggerFactory.getLogger(LlmSettingsService.class);

    private static final String INVALID_FILE_MESSAGE = "LLM settings file is invalid";

    // Jackson 2 mapper for the file format, as in UserInfoService; it is thread-safe once configured.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final String relativePath;

    public LlmSettingsService(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${llm.settings-file-path:llm-settings.json}") String relativePath) {
        this.reader = reader;
        this.writer = writer;
        // A relative path within the FileSandbox root.
        this.relativePath = relativePath;
    }

    @Override
    public LlmSettings get() {
        String json;
        try {
            // Check existence before reading to avoid an error for a missing file.
            if (!reader.exists(relativePath)) {
                return LlmSettings.defaults();
            }
            json = reader.read(relativePath);
        } catch (WorkspacePathException e) {
            // The path is configuration, not request input, so a rejected path is a read failure.
            throw new FileReadException("Unable to read LLM settings file", e);
        }
        try {
            return parse(json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // The cause stays attached for diagnosis; the message never repeats file content.
            throw new FileReadException(INVALID_FILE_MESSAGE, e);
        }
    }

    @Override
    public LlmSettings save(LlmSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Missing field: settings");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("activeProvider", settings.activeProvider().id());
        ObjectNode models = root.putObject("models");
        for (LlmProvider provider : LlmProvider.values()) {
            models.put(provider.id(), settings.model(provider));
        }
        try {
            writer.write(relativePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (JsonProcessingException | WorkspacePathException e) {
            throw new FileWriteException("Unable to write LLM settings file", e);
        }
        log.info("Saved LLM settings: activeProvider={}", settings.activeProvider().id());
        return settings;
    }

    private LlmSettings parse(String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json == null ? "" : json);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(INVALID_FILE_MESSAGE);
        }
        JsonNode active = root.get("activeProvider");
        if (active == null || !active.isTextual()) {
            throw new IllegalArgumentException(INVALID_FILE_MESSAGE);
        }
        Map<LlmProvider, String> models = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            models.put(provider, provider.defaultModel());
        }
        JsonNode saved = root.get("models");
        if (saved != null) {
            if (!saved.isObject()) {
                throw new IllegalArgumentException(INVALID_FILE_MESSAGE);
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = saved.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (!entry.getValue().isTextual()) {
                    throw new IllegalArgumentException(INVALID_FILE_MESSAGE);
                }
                models.put(LlmProvider.fromId(entry.getKey()), entry.getValue().textValue());
            }
        }
        // The record validates every model ID.
        return new LlmSettings(LlmProvider.fromId(active.textValue()), models);
    }
}
