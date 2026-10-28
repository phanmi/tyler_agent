package org.tyler.service.apiKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.WorkspacePathException;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link ApiKeyService} using a temporary sandbox directory.
 * Covers save/load round trips, trimming, missing files, and clearing a key.
 */
class ApiKeyServiceTest {

    @TempDir
    Path tempDir;

    private ApiKeyService newService() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        return new ApiKeyService(sandbox, sandbox, "apikey.txt");
    }

    @Test
    void saveAndGetRoundTrip() throws IOException {
        ApiKeyService service = newService();
        service.save("  sk-abc123  ");

        assertEquals("sk-abc123", service.get());
        assertTrue(service.isConfigured());
    }

    @Test
    void missingFileReturnsEmptyAndNotConfigured() throws IOException {
        ApiKeyService service = newService();

        assertEquals("", service.get());
        assertFalse(service.isConfigured());
    }

    @Test
    void rejectedKeyPathIsReportedAsFileFailureNotClientError() {
        IFileSandboxRead reader = mock(IFileSandboxRead.class);
        IFileSandboxWrite writer = mock(IFileSandboxWrite.class);
        var rejected = new WorkspacePathException("Workspace links and special files are not allowed");
        when(reader.exists("apikey.txt")).thenThrow(rejected);
        doThrow(rejected).when(writer).write(eq("apikey.txt"), anyString());
        ApiKeyService service = new ApiKeyService(reader, writer, "apikey.txt");

        FileReadException read = assertThrowsExactly(FileReadException.class, service::isConfigured);
        assertSame(rejected, read.getCause());
        FileWriteException write = assertThrowsExactly(FileWriteException.class, () -> service.save("sk-abc"));
        assertSame(rejected, write.getCause());
    }

    @Test
    void savingBlankClearsKey() throws IOException {
        ApiKeyService service = newService();
        service.save("sk-abc123");
        assertTrue(service.isConfigured());

        service.save("   ");

        assertEquals("", service.get());
        assertFalse(service.isConfigured());
    }
}
