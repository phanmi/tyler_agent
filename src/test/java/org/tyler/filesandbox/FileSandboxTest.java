package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

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
    void rejectsBlankPath() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> sandbox.read("   "));
    }

    @Test
    void existsReportsFilePresence() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        assertFalse(sandbox.exists("a.txt"));
        sandbox.write("a.txt", "x");
        assertTrue(sandbox.exists("a.txt"));
    }

    @Test
    void normalizedNestedPathsAndAbsolutePathsStayInsideConfiguredRoot() throws IOException {
        Path root = tempDir.resolve("new/workspace");
        FileSandbox sandbox = new FileSandbox(root.toString());
        sandbox.write("unused/../notes/nested/value.txt", "long original");
        sandbox.write("notes/nested/./value.txt", "短");
        assertEquals("短", sandbox.read(root.resolve("notes/nested/value.txt").toString()));
        assertEquals(root.toRealPath().resolve("notes/nested/value.txt"),
                sandbox.resolve("notes/nested/value.txt"));
        assertFalse(Files.exists(root.resolve("unused")));
        assertFalse(sandbox.exists("missing/nested/file.txt"));
        assertEquals(root.toRealPath().resolve("missing/nested/file.txt"),
                sandbox.resolve("missing/nested/file.txt"));
        assertFalse(Files.exists(root.resolve("missing")));
    }

    @Test
    void traversalAndInvalidPathsHaveSafePublicErrors() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.resolve("workspace").toString());
        for (String path : new String[]{"../outside", tempDir.resolve("secret").toString(), "bad\u0000path"}) {
            FileSandboxLinkTest.assertRejected(sandbox, path);
        }
    }

    @Test
    void ioFailuresRetainCausesWithoutExposingAbsolutePaths() throws IOException {
        FileSandbox sandbox = new FileSandbox(tempDir.toString());
        FileReadException read = assertThrowsExactly(FileReadException.class,
                () -> sandbox.read("missing.txt"));
        assertInstanceOf(IOException.class, read.getCause());
        assertEquals("Unable to read workspace file", read.getMessage());
        Files.createDirectory(tempDir.resolve("directory"));
        FileWriteException write = assertThrowsExactly(FileWriteException.class,
                () -> sandbox.write("directory", "not a file"));
        assertInstanceOf(IOException.class, write.getCause());
        assertEquals("Unable to write workspace file", write.getMessage());
    }
}
