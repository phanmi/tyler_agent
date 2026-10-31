package org.tyler.service.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.WorkspacePathException;
import org.tyler.filesandbox.IApplicationOwnedFiles;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.llm.LlmProvider;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stores one API key per provider as plain text in its own sandbox file.
 *
 * <p>The OpenAI file keeps its original {@code openai.key-file-path} setting, so an existing
 * {@code apikey.txt} is used as is. All file access goes through the sandbox, which writes
 * atomically and owner-only on POSIX. Keys and their file contents are never logged.
 */
@Service
public class LlmKeyService implements ILlmKeyService, IApplicationOwnedFiles {

    private static final Logger log = LoggerFactory.getLogger(LlmKeyService.class);

    /** Generous for every provider's key format, and keeps keys far below the worker's frame size. */
    static final int MAX_KEY_LENGTH = 512;

    public static final String INVALID_KEY_MESSAGE =
            "API key must be at most 512 visible ASCII characters with no spaces";

    private final IFileSandboxRead reader;
    private final IFileSandboxWrite writer;
    private final Map<LlmProvider, String> relativePaths = new EnumMap<>(LlmProvider.class);

    public LlmKeyService(
            IFileSandboxRead reader,
            IFileSandboxWrite writer,
            @Value("${openai.key-file-path:apikey.txt}") String openAiPath,
            @Value("${llm.anthropic.key-file-path:anthropic-key.txt}") String anthropicPath,
            @Value("${llm.deepseek.key-file-path:deepseek-key.txt}") String deepSeekPath,
            @Value("${llm.settings-file-path:llm-settings.json}") String settingsPath) {
        this.reader = reader;
        this.writer = writer;
        // Relative paths within the FileSandbox root.
        relativePaths.put(LlmProvider.OPENAI, openAiPath);
        relativePaths.put(LlmProvider.ANTHROPIC, anthropicPath);
        relativePaths.put(LlmProvider.DEEPSEEK, deepSeekPath);
        // A shared file would send one provider's key to another provider, and saving
        // settings over a key file would destroy the key.
        Set<Path> files = new HashSet<>();
        for (String path : List.of(openAiPath, anthropicPath, deepSeekPath, settingsPath)) {
            if (!files.add(normalized(path))) {
                throw new IllegalStateException(
                        "Each provider's API key file and the LLM settings file must be different files");
            }
        }
    }

    @Override
    public List<String> ownedPaths() {
        return List.copyOf(relativePaths.values());
    }

    @Override
    public boolean isConfigured(LlmProvider provider) {
        return !get(provider).isBlank();
    }

    @Override
    public String get(LlmProvider provider) {
        String path = relativePaths.get(provider);
        try {
            // Check existence before reading to avoid an error for a missing file.
            if (!reader.exists(path)) {
                return "";
            }
            String key = reader.read(path);
            return key == null ? "" : key.trim();
        } catch (WorkspacePathException e) {
            // The path is configuration, not request input: report a rejected
            // (e.g. linked) key file as a read failure, not a client error.
            throw new FileReadException("Unable to read API key file", e);
        }
    }

    @Override
    public void save(LlmProvider provider, String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        requireValidKey(normalized);
        String path = relativePaths.get(provider);
        try {
            writer.write(path, normalized);
        } catch (WorkspacePathException e) {
            throw new FileWriteException("Unable to write API key file", e);
        }
        log.info("{} the {} API key in {}", normalized.isEmpty() ? "Cleared" : "Saved", provider.id(), path);
    }

    /**
     * Accepts only visible ASCII ({@code !} to {@code ~}): every provider's keys use it, and SDKs
     * send the key in an HTTP header, where other characters fail as an unclassified error.
     * Rejects without echoing the value, so the key never reaches responses or logs.
     */
    private static void requireValidKey(String key) {
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < '!' || c > '~') {
                throw new IllegalArgumentException(INVALID_KEY_MESSAGE);
            }
        }
    }

    /** Lexical identity, so {@code ./apikey.txt} matches {@code apikey.txt}; case follows the OS. */
    private static Path normalized(String relativePath) {
        try {
            return Path.of(relativePath).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalStateException("Invalid API key or settings file path", e);
        }
    }
}
