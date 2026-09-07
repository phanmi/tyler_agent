package org.tyler.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.exceptionHandler.exception.FileReadException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSandboxTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsInsideRoot() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        sandbox.write("notes/hello.txt", "hi");
        assertEquals("hi", sandbox.read("notes/hello.txt"));
        assertTrue(Files.exists(tempDir.resolve("notes/hello.txt")));
    }

    @Test
    void rejectsPathTraversal() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.write("../escape.txt", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> sandbox.read("../escape.txt"));
    }

    @Test
    void rejectsBlankPath() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> sandbox.read("   "));
    }

    @Test
    void readingMissingFileThrowsFileReadException() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        assertThrows(FileReadException.class,
                () -> sandbox.read("does-not-exist.txt"));
    }
}
