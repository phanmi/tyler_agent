package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSandBoxWriteOnlyTest {

    @TempDir
    Path tempDir;

    @Test
    void delegatesExistsAndWrite() throws Exception {
        FileSandBoxReadAndWrite full = new FileSandBoxReadAndWrite(tempDir.toString());
        FileSandBoxWriteOnly writeOnly = new FileSandBoxWriteOnly(full);
        writeOnly.write("a.txt", "hi");
        assertTrue(writeOnly.exists("a.txt"));
        assertEquals("hi", full.read("a.txt"));
    }
}