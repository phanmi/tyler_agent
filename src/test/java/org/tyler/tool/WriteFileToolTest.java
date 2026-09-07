package org.tyler.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.service.FileSandbox;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteFileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void writesContent() throws Exception {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        WriteFileTool tool = new WriteFileTool(sandbox);
        String result = tool.execute("{\"path\":\"p.txt\",\"content\":\"abc\"}");
        assertEquals("已写入 p.txt", result);
        assertEquals("abc", sandbox.read("p.txt"));
    }

    @Test
    void returnsErrorWhenContentMissing() throws Exception {
        WriteFileTool tool = new WriteFileTool(new FileSandbox(tempDir.toString()));
        String result = tool.execute("{\"path\":\"p.txt\"}");
        assertTrue(result.startsWith("写入失败"));
    }

    @Test
    void exposesNameAndSchema() throws Exception {
        WriteFileTool tool = new WriteFileTool(new FileSandbox(tempDir.toString()));
        assertEquals("writeFile", tool.name());
        assertNotNull(tool.toFunctionTool());
    }
}