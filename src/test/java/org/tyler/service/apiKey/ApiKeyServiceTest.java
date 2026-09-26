package org.tyler.service.apiKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.filesandbox.FileSandbox;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void savingBlankClearsKey() throws IOException {
        ApiKeyService service = newService();
        service.save("sk-abc123");
        assertTrue(service.isConfigured());

        service.save("   ");

        assertEquals("", service.get());
        assertFalse(service.isConfigured());
    }
}
