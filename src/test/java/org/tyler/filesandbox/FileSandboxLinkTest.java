package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.tyler.exceptionHandler.exception.WorkspacePathException;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

class FileSandboxLinkTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsExternalLeafWithoutChangingTarget() throws IOException {
        FileSandbox sandbox = sandbox();
        Path outside = Files.writeString(tempDir.resolve("private.txt"), "keep");
        symbolicLink(tempDir.resolve("workspace/link"), outside);
        assertRejected(sandbox, "link");
        assertEquals("keep", Files.readString(outside));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDirectoryLinksIncludingNewDescendants(boolean internal) throws IOException {
        FileSandbox sandbox = sandbox();
        Path destination = Files.createDirectory(tempDir.resolve(internal ? "workspace/actual" : "outside"));
        Files.writeString(destination.resolve("existing.txt"), "keep");
        symbolicLink(tempDir.resolve("workspace/link"), destination);
        assertRejected(sandbox, "link/existing.txt");
        assertRejected(sandbox, "link/new/nested.txt");
        assertEquals("keep", Files.readString(destination.resolve("existing.txt")));
        assertFalse(Files.exists(destination.resolve("new")));
    }

    @Test
    void rejectsInternalAndDanglingLeafLinks() throws IOException {
        FileSandbox sandbox = sandbox();
        Path target = tempDir.resolve("workspace/target.txt");
        symbolicLink(tempDir.resolve("workspace/link"), target);
        assertRejected(sandbox, "link");
        assertFalse(Files.exists(target));
        Files.writeString(target, "keep");
        assertRejected(sandbox, "link");
        assertEquals("keep", Files.readString(target));
    }

    @Test
    void anchorsConfiguredLinkToItsOriginalRealRoot() throws IOException {
        Path actual = Files.createDirectory(tempDir.resolve("actual"));
        Path alias = tempDir.resolve("configured");
        symbolicLink(alias, actual);
        FileSandbox sandbox = new FileSandbox(alias.toString());
        sandbox.write("nested/value.txt", "keep");
        assertEquals(actual.toRealPath().resolve("nested/value.txt"), sandbox.resolve("nested/value.txt"));
        assertEquals("keep", sandbox.read(alias.resolve("nested/value.txt").toString()));
        Files.delete(alias);
        Path other = Files.createDirectory(tempDir.resolve("other"));
        symbolicLink(alias, other);
        sandbox.write("after.txt", "original root");
        assertEquals("original root", Files.readString(actual.resolve("after.txt")));
        assertFalse(Files.exists(other.resolve("after.txt")));
    }

    @Test
    void rejectsRealRootReplacedWithLink() throws IOException {
        FileSandbox sandbox = sandbox();
        Path workspace = tempDir.resolve("workspace");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        // Probe link support before moving the root, so a platform skip remains harmless.
        symbolicLink(tempDir.resolve("probe"), outside);
        Files.move(workspace, tempDir.resolve("original"));
        symbolicLink(workspace, outside);
        assertRejected(sandbox, "file.txt");
        assertFalse(Files.exists(outside.resolve("file.txt")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionAndPreservesExternalFiles() throws Exception {
        FileSandbox sandbox = sandbox();
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("existing.txt"), "keep");
        Path junction = tempDir.resolve("workspace/junction");
        junction(junction, outside);
        try {
            assertRejected(sandbox, "junction");
            assertRejected(sandbox, "junction/existing.txt");
            assertRejected(sandbox, "junction/new/nested.txt");
            assertEquals("keep", Files.readString(outside.resolve("existing.txt")));
            assertFalse(Files.exists(outside.resolve("new")));
        } finally {
            Files.delete(junction);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void acceptsConfiguredWindowsJunctionAsRoot() throws Exception {
        Path actual = Files.createDirectory(tempDir.resolve("actual"));
        Path alias = tempDir.resolve("configured");
        junction(alias, actual);
        try {
            FileSandbox sandbox = new FileSandbox(alias.toString());
            sandbox.write("nested/file.txt", "keep");
            assertEquals("keep", Files.readString(actual.resolve("nested/file.txt")));
        } finally {
            Files.delete(alias);
        }
    }

    private FileSandbox sandbox() throws IOException {
        return new FileSandbox(tempDir.resolve("workspace").toString());
    }

    static void assertRejected(FileSandbox sandbox, String path) {
        assertSafe(assertThrowsExactly(WorkspacePathException.class, () -> sandbox.resolve(path)));
        assertSafe(assertThrowsExactly(WorkspacePathException.class, () -> sandbox.exists(path)));
        assertSafe(assertThrowsExactly(WorkspacePathException.class, () -> sandbox.read(path)));
        assertSafe(assertThrowsExactly(WorkspacePathException.class, () -> sandbox.write(path, "overwrite")));
    }

    private static void assertSafe(IllegalArgumentException error) {
        assertFalse(error.getMessage().contains("/"));
        assertFalse(error.getMessage().contains("\\"));
    }

    static void symbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException e) {
            abort("Filesystem does not support symbolic links");
        } catch (FileSystemException e) {
            String reason = String.valueOf(e.getReason()).toLowerCase(java.util.Locale.ROOT);
            if (reason.contains("privilege") || reason.contains("not permitted") || reason.contains("not supported")) {
                abort("Symbolic link creation unavailable: " + reason);
            }
            throw e;
        }
    }

    static void junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Junction creation timed out");
            assertEquals(0, process.exitValue(), "Windows junction creation failed");
        } finally {
            process.destroyForcibly();
        }
    }
}
