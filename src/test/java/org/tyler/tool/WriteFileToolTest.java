package org.tyler.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.filesandbox.FileSandBoxReadAndWrite;
import org.tyler.filesandbox.FileSandBoxWriteOnly;
import org.tyler.filesandbox.exceptions.FileWriteException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WriteFileToolTest {

    @TempDir
    Path tempDir;

    @Test
    void writesContent() throws Exception {
        FileSandBoxReadAndWrite full = new FileSandBoxReadAndWrite(tempDir.toString());
        WriteFileTool tool = new WriteFileTool(new FileSandBoxWriteOnly(full));
        String result = tool.execute("{\"path\":\"p.txt\",\"content\":\"abc\"}");
        assertEquals("已写入 p.txt", result);
        assertEquals("abc", full.read("p.txt"));
    }

    @Test
    void throwsWhenContentMissing() throws Exception {
        WriteFileTool tool = new WriteFileTool(
                new FileSandBoxWriteOnly(new FileSandBoxReadAndWrite(tempDir.toString())));
        assertThrows(FileWriteException.class,
                () -> tool.execute("{\"path\":\"p.txt\"}"));
    }

    @Test
    void throwsWhenPathMissing() throws Exception {
        WriteFileTool tool = new WriteFileTool(
                new FileSandBoxWriteOnly(new FileSandBoxReadAndWrite(tempDir.toString())));
        assertThrows(FileWriteException.class,
                () -> tool.execute("{\"content\":\"x\"}"));
    }

    @Test
    void throwsWhenJsonInvalid() throws Exception {
        WriteFileTool tool = new WriteFileTool(
                new FileSandBoxWriteOnly(new FileSandBoxReadAndWrite(tempDir.toString())));
        assertThrows(FileWriteException.class,
                () -> tool.execute("not-json"));
    }

    @Test
    void exposesNameAndSchema() throws Exception {
        WriteFileTool tool = new WriteFileTool(
                new FileSandBoxWriteOnly(new FileSandBoxReadAndWrite(tempDir.toString())));
        assertEquals("writeFile", tool.name());
        assertNotNull(tool.toFunctionTool());
    }
}
