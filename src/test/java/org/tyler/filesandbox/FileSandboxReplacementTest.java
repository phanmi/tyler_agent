package org.tyler.filesandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tyler.exceptionHandler.exception.FileWriteException;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Staged, atomically published replacement writes (issue #50). */
class FileSandboxReplacementTest {
    @TempDir
    Path tempDir;

    private Path workspace() {
        return tempDir.resolve("workspace");
    }

    private FileSandbox sandbox(FileSandbox.WriteFaults faults) throws IOException {
        return new FileSandbox(workspace().toString(), faults);
    }

    /** Asserts the workspace holds only the target and nothing was written beside it. */
    private void assertOnlyTarget(String expectedContent) throws IOException {
        assertEquals(expectedContent, Files.readString(workspace().resolve("notes/data.txt")));
        try (var entries = Files.list(workspace().resolve("notes"))) {
            assertEquals(List.of("data.txt"), entries.map(p -> p.getFileName().toString()).toList());
        }
        try (var entries = Files.list(tempDir)) {
            assertEquals(List.of(workspace()), entries.toList());
        }
    }

    @Test
    void failureAfterStagingBeganKeepsPreviousContentAndRemovesStagedFile() throws IOException {
        sandbox(FileSandbox.WriteFaults.NONE).write("notes/data.txt", "old");
        IOException injected = new IOException("disk full");
        FileSandbox failing = sandbox(step -> {
            if (step == FileSandbox.WriteStep.STAGED) {
                throw injected;
            }
        });

        FileWriteException error = assertThrowsExactly(FileWriteException.class,
                () -> failing.write("notes/data.txt", "new content"));
        assertSame(injected, error.getCause());
        assertEquals("Unable to write workspace file", error.getMessage());
        assertOnlyTarget("old");
    }

    @Test
    void unsupportedAtomicPublishFailsWithoutRetryOrFallback() throws IOException {
        sandbox(FileSandbox.WriteFaults.NONE).write("notes/data.txt", "old");
        AtomicInteger publishAttempts = new AtomicInteger();
        var unsupported = new AtomicMoveNotSupportedException(null, null, "not supported");
        FileSandbox sandbox = sandbox(step -> {
            if (step == FileSandbox.WriteStep.PUBLISH) {
                publishAttempts.incrementAndGet();
                throw unsupported;
            }
        });

        FileWriteException error = assertThrowsExactly(FileWriteException.class,
                () -> sandbox.write("notes/data.txt", "new"));
        assertSame(unsupported, error.getCause());
        assertEquals(1, publishAttempts.get());
        assertOnlyTarget("old");
    }

    @Test
    void transientlyDeniedPublishIsRetriedAndSucceeds() throws IOException {
        AtomicInteger publishAttempts = new AtomicInteger();
        FileSandbox sandbox = sandbox(step -> {
            if (step == FileSandbox.WriteStep.PUBLISH && publishAttempts.incrementAndGet() <= 2) {
                throw new AccessDeniedException("locked by another program");
            }
        });
        sandbox.write("notes/data.txt", "new");
        assertEquals(3, publishAttempts.get());
        assertOnlyTarget("new");
    }

    @Test
    void sharingViolationIsRetriedLikeAccessDenied() throws IOException {
        sandbox(FileSandbox.WriteFaults.NONE).write("notes/data.txt", "old");
        AtomicInteger publishAttempts = new AtomicInteger();
        FileSandbox sandbox = sandbox(step -> {
            if (step == FileSandbox.WriteStep.PUBLISH && publishAttempts.incrementAndGet() <= 2) {
                // How NIO reports Windows ERROR_SHARING_VIOLATION from MoveFileEx.
                throw new FileSystemException("data.txt", null,
                        "The process cannot access the file because it is being used by another process");
            }
        });
        sandbox.write("notes/data.txt", "new");
        assertEquals(3, publishAttempts.get());
        assertOnlyTarget("new");
    }

    @Test
    void specificFileSystemFailureIsNotRetried() throws IOException {
        sandbox(FileSandbox.WriteFaults.NONE).write("notes/data.txt", "old");
        AtomicInteger publishAttempts = new AtomicInteger();
        var missing = new NoSuchFileException("data.txt");
        FileSandbox sandbox = sandbox(step -> {
            if (step == FileSandbox.WriteStep.PUBLISH) {
                publishAttempts.incrementAndGet();
                throw missing;
            }
        });

        FileWriteException error = assertThrowsExactly(FileWriteException.class,
                () -> sandbox.write("notes/data.txt", "new"));
        assertSame(missing, error.getCause());
        assertEquals(1, publishAttempts.get());
        assertOnlyTarget("old");
    }

    @Test
    void targetNameNearLengthLimitIsStillReplaced() throws IOException {
        FileSandbox sandbox = sandbox(FileSandbox.WriteFaults.NONE);
        // 244 bytes: valid on its own, but too long with the 18-byte staging decoration.
        String name = "n".repeat(240) + ".txt";
        sandbox.write("notes/" + name, "old");
        sandbox.write("notes/" + name, "new");
        assertEquals("new", sandbox.read("notes/" + name));
        try (var entries = Files.list(workspace().resolve("notes"))) {
            assertEquals(List.of(name), entries.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void persistentlyDeniedPublishFailsAfterBoundedRetriesKeepingPreviousContent() throws IOException {
        sandbox(FileSandbox.WriteFaults.NONE).write("notes/data.txt", "old");
        AtomicInteger publishAttempts = new AtomicInteger();
        FileSandbox sandbox = sandbox(step -> {
            if (step == FileSandbox.WriteStep.PUBLISH) {
                publishAttempts.incrementAndGet();
                throw new AccessDeniedException("locked by another program");
            }
        });

        FileWriteException error = assertThrowsExactly(FileWriteException.class,
                () -> sandbox.write("notes/data.txt", "new"));
        assertInstanceOf(AccessDeniedException.class, error.getCause());
        assertEquals(4, publishAttempts.get());
        assertOnlyTarget("old");
    }

    @Test
    void cleanupFailureIsSuppressedWithoutReplacingOriginalCause() throws IOException {
        sandbox(FileSandbox.WriteFaults.NONE).write("notes/data.txt", "old");
        IOException original = new IOException("staging failed");
        IOException cleanup = new IOException("cleanup failed");
        FileSandbox sandbox = sandbox(step -> {
            switch (step) {
                case STAGED -> throw original;
                case CLEANUP -> throw cleanup;
                default -> { }
            }
        });

        FileWriteException error = assertThrowsExactly(FileWriteException.class,
                () -> sandbox.write("notes/data.txt", "new"));
        assertSame(original, error.getCause());
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
        assertEquals("old", Files.readString(workspace().resolve("notes/data.txt")));
        // The staged file is left behind only because cleanup was made to fail.
        try (var entries = Files.list(workspace().resolve("notes"))) {
            assertEquals(2, entries.count());
        }
    }

    @Test
    void replacementIsOwnerOnlyOnPosixEvenIfPreviousFileWasReadableByOthers() throws IOException {
        assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "POSIX permissions are unsupported on this filesystem");
        FileSandbox sandbox = sandbox(FileSandbox.WriteFaults.NONE);
        sandbox.write("notes/data.txt", "old");
        Path target = workspace().resolve("notes/data.txt");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        sandbox.write("notes/data.txt", "sk-secret");
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target));
        assertOnlyTarget("sk-secret");
    }

    @Test
    void concurrentReadersSeeCompleteOldOrNewContentAcrossRepeatedWrites() throws Exception {
        FileSandbox sandbox = sandbox(FileSandbox.WriteFaults.NONE);
        String first = "a".repeat(256 * 1024);
        String second = "b".repeat(192 * 1024);
        sandbox.write("notes/data.txt", first);
        AtomicBoolean writing = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> reads = pool.submit(() -> {
                int count = 0;
                while (writing.get()) {
                    String seen = sandbox.read("notes/data.txt");
                    assertTrue(seen.equals(first) || seen.equals(second),
                            "Observed a partial replacement of length " + seen.length());
                    count++;
                }
                return count;
            });
            Future<?> writes = pool.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        sandbox.write("notes/data.txt", i % 2 == 0 ? second : first);
                    }
                } finally {
                    writing.set(false);
                }
                return null;
            });
            writes.get(60, TimeUnit.SECONDS);
            assertTrue(reads.get(60, TimeUnit.SECONDS) > 0);
        } finally {
            writing.set(false);
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertOnlyTarget(first);
    }
}
