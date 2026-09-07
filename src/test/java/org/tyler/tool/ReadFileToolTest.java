package org.tyler.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.service.FileSandbox;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void throwsWhenFileMissing() throws Exception {
        ReadFileTool tool = new ReadFileTool(new FileSandbox(tempDir.toString()));
        assertThrows(FileReadException.class,
                () -> tool.execute("{\"path\":\"nope.txt\"}"));
    }

    @Test
    void throwsWhenPathMissing() throws Exception {
        ReadFileTool tool = new ReadFileTool(new FileSandbox(tempDir.toString()));
        assertThrows(FileReadException.class, () -> tool.execute("{}"));
    }

    @Test
    void throwsWhenJsonInvalid() throws Exception {
        ReadFileTool tool = new ReadFileTool(new FileSandbox(tempDir.toString()));
        assertThrows(FileReadException.class, () -> tool.execute("not-json"));
    }

    @Test
    void exposesNameAndSchema() throws Exception {
        ReadFileTool tool = new ReadFileTool(new FileSandbox(tempDir.toString()));
        assertEquals("readFile", tool.name());
        assertNotNull(tool.toFunctionTool());
    }
}
