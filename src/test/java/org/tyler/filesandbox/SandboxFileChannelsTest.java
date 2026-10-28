package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SandboxFileChannelsTest {
    @TempDir
    Path tempDir;

    @Test
    void replaceAtomicallyRenamesStagedSiblingOverNestedTarget() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        Path directory = Files.createDirectories(root.resolve("a/b"));
        Path target = Files.writeString(directory.resolve("data.txt"), "old");
        Path staged = Files.writeString(directory.resolve(".data.txt.1.tmp"), "new");
        SandboxFileChannels.replace(root, staged, target, key(root));
        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(staged));
    }

    @ParameterizedTest
    @EnumSource(value = StandardOpenOption.class, names = {"READ", "WRITE"})
    void noFollowOpenRejectsLeafSubstitutedAfterValidation(StandardOpenOption operation) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        FileSandbox sandbox = new FileSandbox(root.toString());
        Path validated = sandbox.resolve("file.txt");
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "keep");
        FileSandboxLinkTest.symbolicLink(validated, outside);
        assertThrows(IOException.class, () -> {
            try (var ignored = SandboxFileChannels.open(root, validated, key(root),
                    Set.of(operation, LinkOption.NOFOLLOW_LINKS))) {
                fail("Link must not be opened");
            }
        });
        assertEquals("keep", Files.readString(outside));
    }

    @Test
    void secureTraversalRejectsAncestorSubstitutedAfterValidation() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        requireSecureStreams(root);
        Files.createDirectory(root.resolve("parent"));
        FileSandbox sandbox = new FileSandbox(root.toString());
        Path validated = sandbox.resolve("parent/file.txt");
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("file.txt"), "keep");
        Files.move(root.resolve("parent"), root.resolve("original"));
        FileSandboxLinkTest.symbolicLink(root.resolve("parent"), outside);
        assertThrows(IOException.class, () -> {
            try (var ignored = SandboxFileChannels.open(root, validated, key(root),
                    Set.of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                fail("Linked ancestor must not be opened");
            }
        });
        assertEquals("keep", Files.readString(outside.resolve("file.txt")));
    }

    @Test
    void secureRootHandleRejectsReplacedDirectory() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("workspace")).toRealPath();
        requireSecureStreams(root);
        Object originalKey = key(root);
        assumeTrue(originalKey != null, "Provider has no stable file key");
        Files.move(root, tempDir.resolve("original"));
        Files.createDirectory(root);
        assertThrowsExactly(IllegalArgumentException.class, () -> {
            try (var ignored = SandboxFileChannels.open(root, root.resolve("file.txt"), originalKey,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, LinkOption.NOFOLLOW_LINKS))) {
                fail("Replaced root must not be opened");
            }
        });
        assertFalse(Files.exists(root.resolve("file.txt")));
    }

    private static Object key(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static void requireSecureStreams(Path root) throws IOException {
        try (var stream = Files.newDirectoryStream(root)) {
            assumeTrue(stream instanceof SecureDirectoryStream<?>,
                    "Provider has no SecureDirectoryStream; portable fallback is covered separately");
        }
    }
}
