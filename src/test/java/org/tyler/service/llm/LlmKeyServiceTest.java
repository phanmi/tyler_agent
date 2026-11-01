package org.tyler.service.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.WorkspacePathException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.model.llm.LlmProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tests per-provider key files in a temporary sandbox, the key rule, file guards, and log redaction. */
@ExtendWith(OutputCaptureExtension.class)
class LlmKeyServiceTest {

    private static final String KEY = "SYNTHETIC_FIXTURE_KEY_NEVER_SEND_TO_PROVIDER";

    @TempDir
    Path tempDir;

    private LlmKeyService newService() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        return new LlmKeyService(sandbox, sandbox, "apikey.txt", "anthropic-key.txt", "deepseek-key.txt",
                "llm-settings.json");
    }

    private static LlmKeyService withPaths(String openai, String anthropic, String deepseek, String settings) {
        return new LlmKeyService(mock(IFileSandboxRead.class), mock(IFileSandboxWrite.class),
                openai, anthropic, deepseek, settings);
    }

    @Test
    void eachProviderUsesItsOwnFileAndOpenAiKeepsApikeyTxt() throws IOException {
        Files.writeString(tempDir.resolve("apikey.txt"), "existing-openai\n");
        LlmKeyService service = newService();

        service.save(LlmProvider.ANTHROPIC, "  " + KEY + "-a  ");
        service.save(LlmProvider.DEEPSEEK, KEY + "-d");

        assertEquals("existing-openai", service.get(LlmProvider.OPENAI));
        assertEquals(KEY + "-a", Files.readString(tempDir.resolve("anthropic-key.txt")));
        assertEquals(KEY + "-d", service.get(LlmProvider.DEEPSEEK));
        assertTrue(service.isConfigured(LlmProvider.ANTHROPIC));
    }

    @Test
    void missingFilesAreNotConfiguredAndBlankClears() throws IOException {
        LlmKeyService service = newService();
        for (LlmProvider provider : LlmProvider.values()) {
            assertFalse(service.isConfigured(provider));
            assertEquals("", service.get(provider));
        }
        service.save(LlmProvider.DEEPSEEK, KEY);
        service.save(LlmProvider.DEEPSEEK, "   ");
        assertFalse(service.isConfigured(LlmProvider.DEEPSEEK));
        service.save(LlmProvider.DEEPSEEK, null);
        assertEquals("", service.get(LlmProvider.DEEPSEEK));
    }

    @Test
    void acceptsAKeyAtTheLengthLimit() throws IOException {
        LlmKeyService service = newService();
        String key = "k".repeat(LlmKeyService.MAX_KEY_LENGTH);
        service.save(LlmProvider.ANTHROPIC, key);
        assertEquals(key, service.get(LlmProvider.ANTHROPIC));
    }

    @Test
    void acceptsEveryVisibleAsciiCharacter() throws IOException {
        LlmKeyService service = newService();
        StringBuilder key = new StringBuilder();
        for (char c = '!'; c <= '~'; c++) {
            key.append(c);
        }
        service.save(LlmProvider.OPENAI, key.toString());
        assertEquals(key.toString(), service.get(LlmProvider.OPENAI));
    }

    @ParameterizedTest
    @ValueSource(strings = {"inner space", "line\nbreak", "tab\there", "bell\u0007", "nbsp x", "del\u007f",
            "line sep", "accenté", "emoji🔑", "fullwidthＡ", "zero​width"})
    void rejectedKeysLeaveTheSavedKeyAndAreNeverEchoed(String suffix, CapturedOutput output) throws IOException {
        LlmKeyService service = newService();
        service.save(LlmProvider.ANTHROPIC, KEY);

        var error = assertThrows(IllegalArgumentException.class,
                // Trailing characters would be trimmed, so the rejected one sits inside the key.
                () -> service.save(LlmProvider.ANTHROPIC, "REJECTED_MARKER" + suffix + "end"));

        assertEquals(LlmKeyService.INVALID_KEY_MESSAGE, error.getMessage());
        assertEquals(KEY, service.get(LlmProvider.ANTHROPIC));
        assertFalse(output.getAll().contains("REJECTED_MARKER"));
        assertFalse(output.getAll().contains(KEY));
    }

    @Test
    void rejectsAKeyOverTheLengthLimit() throws IOException {
        LlmKeyService service = newService();
        assertThrows(IllegalArgumentException.class,
                () -> service.save(LlmProvider.OPENAI, "k".repeat(LlmKeyService.MAX_KEY_LENGTH + 1)));
        assertFalse(Files.exists(tempDir.resolve("apikey.txt")));
    }

    @ParameterizedTest
    @CsvSource({
            "apikey.txt, apikey.txt, deepseek-key.txt, llm-settings.json",
            "apikey.txt, anthropic-key.txt, ./anthropic-key.txt, llm-settings.json",
            "apikey.txt, anthropic-key.txt, deepseek-key.txt, apikey.txt",
            "apikey.txt, anthropic-key.txt, deepseek-key.txt, keys/../deepseek-key.txt"})
    void sharedKeyOrSettingsFilesAreAStartupError(String openai, String anthropic, String deepseek, String settings) {
        assertThrows(IllegalStateException.class, () -> withPaths(openai, anthropic, deepseek, settings));
    }

    @Test
    void sameFileInADifferentCaseIsAStartupErrorOnWindows() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        assertThrows(IllegalStateException.class,
                () -> withPaths("apikey.txt", "APIKEY.TXT", "deepseek-key.txt", "llm-settings.json"));
    }

    @Test
    void keyFilesAreOwnerOnlyOnPosix() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        newService().save(LlmProvider.DEEPSEEK, KEY);
        assertEquals("rw-------",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(tempDir.resolve("deepseek-key.txt"))));
    }

    @Test
    void rejectedKeyPathIsAFileFailureNotAClientError() {
        IFileSandboxRead reader = mock(IFileSandboxRead.class);
        IFileSandboxWrite writer = mock(IFileSandboxWrite.class);
        var rejected = new WorkspacePathException("Workspace links and special files are not allowed");
        when(reader.exists("anthropic-key.txt")).thenThrow(rejected);
        doThrow(rejected).when(writer).write(eq("anthropic-key.txt"), anyString());
        LlmKeyService service = new LlmKeyService(reader, writer, "apikey.txt", "anthropic-key.txt",
                "deepseek-key.txt", "llm-settings.json");

        FileReadException read = assertThrowsExactly(FileReadException.class,
                () -> service.isConfigured(LlmProvider.ANTHROPIC));
        assertSame(rejected, read.getCause());
        FileWriteException write = assertThrowsExactly(FileWriteException.class,
                () -> service.save(LlmProvider.ANTHROPIC, KEY));
        assertSame(rejected, write.getCause());
    }

    @Test
    void savingLogsTheProviderButNeverTheKey(CapturedOutput output) throws IOException {
        newService().save(LlmProvider.ANTHROPIC, KEY);
        assertTrue(output.getAll().contains("Saved the anthropic API key"));
        assertFalse(output.getAll().contains(KEY));
    }
}
