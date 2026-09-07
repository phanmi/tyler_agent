package org.tyler.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.service.FileSandbox;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void readsExistingFile() throws Exception {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        sandbox.write("prompts/a.txt", "hello world");
        ReadFileTool tool = new ReadFileTool(sandbox);
        assertEquals("hello world", tool.execute("{\"path\":\"prompts/a.txt\"}"));
    }

    @Test
    void returnsErrorWhenMissing() throws Exception {
        ReadFileTool tool = new ReadFileTool(new FileSandbox(tempDir.toString()));
        String result = tool.execute("{\"path\":\"nope.txt\"}");
        assertTrue(result.startsWith("读取"));
    }

    @Test
    void exposesNameAndSchema() throws Exception {
        ReadFileTool tool = new ReadFileTool(new FileSandbox(tempDir.toString()));
        assertEquals("readFile", tool.name());
        assertNotNull(tool.toFunctionTool());
    }
}