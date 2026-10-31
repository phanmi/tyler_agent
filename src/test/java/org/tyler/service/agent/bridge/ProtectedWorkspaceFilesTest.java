package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.exceptionHandler.exception.WorkspacePathException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.filesandbox.IApplicationOwnedFiles;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.service.agent.bridge.handler.FileReadHandler;
import org.tyler.service.agent.bridge.handler.FileWriteHandler;
import org.tyler.service.llm.LlmKeyService;
import org.tyler.service.llm.LlmSettingsService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

/** Keys, provider settings and chat history stay out of reach of the model's file tools. */
class ProtectedWorkspaceFilesTest {

    @TempDir
    Path tempDir;

    private ProtectedWorkspaceFiles newProtectedFiles() throws IOException {
        IApplicationOwnedFiles keys = () -> List.of("apikey.txt", "anthropic-key.txt", "deepseek-key.txt");
        IApplicationOwnedFiles settingsAndHistory = () -> List.of("llm-settings.json", "chat-history.json");
        return new ProtectedWorkspaceFiles(new FileSandbox(tempDir.toString()), List.of(keys, settingsAndHistory));
    }

    @ParameterizedTest
    @ValueSource(strings = {"apikey.txt", "anthropic-key.txt", "deepseek-key.txt", "llm-settings.json",
            "chat-history.json", "./apikey.txt", "notes/../anthropic-key.txt", "APIKEY.TXT",
            "DeepSeek-Key.txt", "apikey.txt.", "llm-settings.json. ."})
    void reservedFilesAndTheirAliasesAreRefused(String path) throws IOException {
        var error = assertThrowsExactly(WorkspacePathException.class,
                () -> newProtectedFiles().requireUnprotected(path));

        assertEquals(ProtectedWorkspaceFiles.RESERVED_MESSAGE, error.getMessage());
    }

    @Test
    void reservedFilesAreRefusedWhetherOrNotTheyExist() throws IOException {
        Files.writeString(tempDir.resolve("anthropic-key.txt"), "SYNTHETIC_FIXTURE_KEY_NEVER_SEND_TO_PROVIDER");
        ProtectedWorkspaceFiles files = newProtectedFiles();

        assertThrowsExactly(WorkspacePathException.class, () -> files.requireUnprotected("Anthropic-Key.txt"));
        assertThrowsExactly(WorkspacePathException.class, () -> files.requireUnprotected("deepseek-key.txt"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "notes/apikey.txt", "apikey.txt.bak", "my-chat-history.json"})
    void ordinaryWorkspaceFilesStayAvailable(String path) throws IOException {
        assertDoesNotThrow(() -> newProtectedFiles().requireUnprotected(path));
    }

    @Test
    void theOwningServicesDeclareTheirFiles() {
        var keys = new LlmKeyService(mock(IFileSandboxRead.class), mock(IFileSandboxWrite.class),
                "apikey.txt", "anthropic-key.txt", "deepseek-key.txt", "llm-settings.json");
        var settings = new LlmSettingsService(mock(IFileSandboxRead.class),
                mock(IFileSandboxWrite.class), "llm-settings.json");

        assertEquals(List.of("apikey.txt", "anthropic-key.txt", "deepseek-key.txt"), keys.ownedPaths());
        assertEquals(List.of("llm-settings.json"), settings.ownedPaths());
    }

    @Test
    void handlersRefuseBeforeTouchingTheSandbox() throws IOException {
        IFileSandboxRead reader = mock(IFileSandboxRead.class);
        IFileSandboxWrite writer = mock(IFileSandboxWrite.class);
        ProtectedWorkspaceFiles files = newProtectedFiles();

        assertThrowsExactly(WorkspacePathException.class,
                () -> new FileReadHandler(reader, files).execute(input("file_read").put("path", "apikey.txt")));
        assertThrowsExactly(WorkspacePathException.class, () -> new FileWriteHandler(writer, files)
                .execute(input("file_write").put("path", "llm-settings.json")));

        verify(reader, never()).read(anyString());
        verify(writer, never()).write(anyString(), anyString());
    }
}
