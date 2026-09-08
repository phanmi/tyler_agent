package org.tyler.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.filesandbox.FileSandbox;
import org.tyler.filesandbox.exceptions.FileWriteException;
import org.tyler.tool.fileTool.WriteFileTool;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WriteFileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void writesContent() throws Exception {
        FileSandbox full = new FileSandbox(tempDir.toString());
        WriteFileTool tool = new WriteFileTool(full);
        String result = tool.execute("{\"path\":\"p.txt\",\"content\":\"abc\"}");
        assertEquals("已写入 p.txt", result);
        assertEquals("abc", full.read("p.txt"));
    }

    @Test
    void throwsWhenContentMissing() throws Exception {
        WriteFileTool tool = new WriteFileTool(new FileSandbox(tempDir.toString()));
        assertThrows(FileWriteException.class,
                () -> tool.execute("{\"path\":\"p.txt\"}"));
    }

    @Test
    void throwsWhenPathMissing() throws Exception {
        WriteFileTool tool = new WriteFileTool(new FileSandbox(tempDir.toString()));
        assertThrows(FileWriteException.class,
                () -> tool.execute("{\"content\":\"x\"}"));
    }

    @Test
    void throwsWhenJsonInvalid() throws Exception {
        WriteFileTool tool = new WriteFileTool(new FileSandbox(tempDir.toString()));
        assertThrows(FileWriteException.class,
                () -> tool.execute("not-json"));
    }

    @Test
    void exposesNameAndSchema() throws Exception {
        WriteFileTool tool = new WriteFileTool(new FileSandbox(tempDir.toString()));
        assertEquals("writeFile", tool.name());
        assertNotNull(tool.toFunctionTool());
    }
}
