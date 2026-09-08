package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSandBoxReadOnlyTest {

    @TempDir
    Path tempDir;

    @Test
    void delegatesExistsAndRead() throws Exception {
        FileSandBoxReadAndWrite full = new FileSandBoxReadAndWrite(tempDir.toString());
        full.write("a.txt", "hi");
        FileSandBoxReadOnly readOnly = new FileSandBoxReadOnly(full);
        assertTrue(readOnly.exists("a.txt"));
        assertEquals("hi", readOnly.read("a.txt"));
    }
}