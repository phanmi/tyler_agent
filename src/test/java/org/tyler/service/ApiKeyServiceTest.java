package org.tyler.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.filesandbox.FileSandbox;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对 {@link ApiKeyService} 做基于临时沙盒目录的单元测试：
 * 覆盖 save/get 往返、trim、空 key 判定、文件不存在、空白串清空等路径。
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
