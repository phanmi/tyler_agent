package org.tyler.filesandbox;

import org.tyler.exceptionHandler.exception.WorkspacePathException;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Opens and renames files relative to directory handles where the provider supports it. */
final class SandboxFileChannels {
    // SecureDirectoryStream support is a property of the provider, so it is probed once:
    // providers without it (Windows) skip opening a root directory stream per operation.
    private static final Map<FileSystemProvider, Boolean> SECURE_STREAMS = new ConcurrentHashMap<>();

    private SandboxFileChannels() {
    }

    /** Opens {@code target}; {@code attributes} apply only when the file is created. */
    static SeekableByteChannel open(Path root, Path target, Object rootKey,
            Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
        try (SecureDirectoryStream<Path> secure = secureRoot(root, rootKey)) {
            if (secure != null) {
                return openRelative(secure, root.relativize(target), options, attributes);
            }
        }
        // Windows NIO has no SecureDirectoryStream. NOFOLLOW protects the leaf;
        // FileSandbox checks ancestors before and after opening (see policy doc).
        return Files.newByteChannel(target, options, attributes);
    }

    /**
     * Atomically renames {@code source} over {@code target}; both must be siblings under
     * {@code root}. Where supported, the rename runs relative to no-follow directory
     * handles. Fails with {@link java.nio.file.AtomicMoveNotSupportedException} rather than
     * falling back to a non-atomic copy.
     */
    static void replace(Path root, Path source, Path target, Object rootKey) throws IOException {
        if (!source.getParent().equals(target.getParent())) {
            throw new IllegalArgumentException("Staged and target files must share a directory");
        }
        try (SecureDirectoryStream<Path> secure = secureRoot(root, rootKey)) {
            if (secure != null) {
                moveRelative(secure, root.relativize(source), root.relativize(target));
                return;
            }
        }
        // Windows: MoveFileEx with MOVEFILE_REPLACE_EXISTING. A rename replaces the target
        // entry itself, so a link substituted at the target is replaced, never followed.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Opens a secure handle on {@code root} and checks its identity, or returns null when the
     * provider has no secure directory streams. The caller closes the returned stream.
     */
    private static SecureDirectoryStream<Path> secureRoot(Path root, Object rootKey) throws IOException {
        FileSystemProvider provider = root.getFileSystem().provider();
        if (Boolean.FALSE.equals(SECURE_STREAMS.get(provider))) {
            return null;
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
            SECURE_STREAMS.put(provider, false);
            stream.close();
            return null;
        }
        SECURE_STREAMS.put(provider, true);
        try {
            Object openedKey = secure.getFileAttributeView(BasicFileAttributeView.class)
                    .readAttributes().fileKey();
            if (rootKey != null && !rootKey.equals(openedKey)) {
                throw new WorkspacePathException("Workspace root has changed");
            }
            return secure;
        } catch (IOException | RuntimeException e) {
            secure.close();
            throw e;
        }
    }

    private static void moveRelative(SecureDirectoryStream<Path> directory,
            Path source, Path target) throws IOException {
        if (target.getNameCount() == 1) {
            // renameat on POSIX providers: atomic, and replaces an existing target.
            directory.move(source, directory, target);
            return;
        }
        try (var child = directory.newDirectoryStream(target.getName(0), LinkOption.NOFOLLOW_LINKS)) {
            moveRelative(child, source.subpath(1, source.getNameCount()),
                    target.subpath(1, target.getNameCount()));
        }
    }

    private static SeekableByteChannel openRelative(SecureDirectoryStream<Path> directory,
            Path relative, Set<? extends OpenOption> options, FileAttribute<?>... attributes)
            throws IOException {
        if (relative.getNameCount() == 1) {
            return directory.newByteChannel(relative, options, attributes);
        }
        try (var child = directory.newDirectoryStream(relative.getName(0), LinkOption.NOFOLLOW_LINKS)) {
            return openRelative(child, relative.subpath(1, relative.getNameCount()), options, attributes);
        }
    }
}
