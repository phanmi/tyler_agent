package org.tyler.service.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.WorkspacePathException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.llm.LlmProvider;
import org.tyler.model.llm.LlmSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests the settings file in a temporary sandbox: defaults, round trips, and invalid files. */
class LlmSettingsServiceTest {

    @TempDir
    Path tempDir;

    private LlmSettingsService newService() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        return new LlmSettingsService(sandbox, sandbox, "llm-settings.json");
    }

    private static LlmSettings custom() {
        return new LlmSettings(LlmProvider.ANTHROPIC, Map.of(
                LlmProvider.OPENAI, "gpt-5.6",
                LlmProvider.ANTHROPIC, "claude-opus-5-5",
                LlmProvider.DEEPSEEK, "deepseek-v4-pro"));
    }

    @Test
    void missingFileReturnsDefaults() throws IOException {
        assertEquals(LlmSettings.defaults(), newService().get());
    }

    @Test
    void saveAndGetRoundTripAsReadableJson() throws IOException {
        LlmSettingsService service = newService();

        assertEquals(custom(), service.save(custom()));
        assertEquals(custom(), service.get());
        String json = Files.readString(tempDir.resolve("llm-settings.json"));
        assertTrue(json.contains("\"activeProvider\" : \"anthropic\""));
        assertTrue(json.contains("\"deepseek\" : \"deepseek-v4-pro\""));
    }

    @Test
    void settingsAndKeysSurviveABackendRestart() throws IOException {
        newService().save(custom());
        FileSandbox before = new FileSandbox(tempDir.toString());
        new LlmKeyService(before, before, "apikey.txt", "anthropic-key.txt", "deepseek-key.txt", "llm-settings.json")
                .save(LlmProvider.ANTHROPIC, "SYNTHETIC_FIXTURE_KEY");

        // Fresh sandbox and service instances read only what was written to disk.
        FileSandbox after = new FileSandbox(tempDir.toString());
        assertEquals(custom(), new LlmSettingsService(after, after, "llm-settings.json").get());
        var keys = new LlmKeyService(after, after, "apikey.txt", "anthropic-key.txt", "deepseek-key.txt",
                "llm-settings.json");
        assertTrue(keys.isConfigured(LlmProvider.ANTHROPIC));
        assertEquals("SYNTHETIC_FIXTURE_KEY", keys.get(LlmProvider.ANTHROPIC));
    }

    @Test
    void providersMissingFromTheFileUseTheirDefaultModel() throws IOException {
        Files.writeString(tempDir.resolve("llm-settings.json"),
                "{\"activeProvider\":\"deepseek\",\"models\":{\"deepseek\":\"deepseek-v4-pro\"}}");

        LlmSettings settings = newService().get();

        assertEquals(LlmProvider.DEEPSEEK, settings.activeProvider());
        assertEquals("deepseek-v4-pro", settings.model(LlmProvider.DEEPSEEK));
        assertEquals("gpt-6-sol", settings.model(LlmProvider.OPENAI));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "[]", "\"openai\"", "{}", "{\"activeProvider\":3}",
            "{\"activeProvider\":\"mistral\"}", "{\"activeProvider\":\"openai\",\"models\":[]}",
            "{\"activeProvider\":\"openai\",\"models\":{\"mistral\":\"m\"}}",
            "{\"activeProvider\":\"openai\",\"models\":{\"openai\":7}}",
            "{\"activeProvider\":\"openai\",\"models\":{\"openai\":\"bad model\"}}"})
    void invalidFilesFailInsteadOfFallingBack(String content) throws IOException {
        Files.writeString(tempDir.resolve("llm-settings.json"), content);

        var error = assertThrowsExactly(FileReadException.class, () -> newService().get());

        assertEquals("LLM settings file is invalid", error.getMessage());
        assertNotNull(error.getCause());
    }

    @Test
    void savingValidSettingsRepairsAnInvalidFile() throws IOException {
        Files.writeString(tempDir.resolve("llm-settings.json"), "{broken");
        LlmSettingsService service = newService();

        service.save(custom());

        assertEquals(custom(), service.get());
    }

    @Test
    void savingNothingIsAClientError() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> newService().save(null));
    }

    @Test
    void rejectedSettingsPathIsAFileFailureNotAClientError() {
        IFileSandboxRead reader = mock(IFileSandboxRead.class);
        IFileSandboxWrite writer = mock(IFileSandboxWrite.class);
        var rejected = new WorkspacePathException("Workspace links and special files are not allowed");
        when(reader.exists("llm-settings.json")).thenThrow(rejected);
        doThrow(rejected).when(writer).write(eq("llm-settings.json"), anyString());
        LlmSettingsService service = new LlmSettingsService(reader, writer, "llm-settings.json");

        assertSame(rejected, assertThrowsExactly(FileReadException.class, service::get).getCause());
        assertSame(rejected, assertThrowsExactly(FileWriteException.class, () -> service.save(custom())).getCause());
    }
}
