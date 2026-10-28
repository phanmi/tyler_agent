package org.tyler.filesandbox;

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
import java.util.Set;

/** Opens and renames files relative to directory handles where the provider supports it. */
final class SandboxFileChannels {
    private SandboxFileChannels() {
    }

    /** Opens {@code target}; {@code attributes} apply only when the file is created. */
    static SeekableByteChannel open(Path root, Path target, Object rootKey,
            Set<? extends OpenOption> options, FileAttribute<?>... attributes) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                Object openedKey = secure.getFileAttributeView(BasicFileAttributeView.class)
                        .readAttributes().fileKey();
                if (rootKey != null && !rootKey.equals(openedKey)) {
                    throw new IllegalArgumentException("Workspace root has changed");
                }
                return openRelative(secure, root.relativize(target), options, attributes);
            }
            // Windows NIO has no SecureDirectoryStream. NOFOLLOW protects the leaf;
            // FileSandbox checks ancestors before and after opening (see policy doc).
            return Files.newByteChannel(target, options, attributes);
        }
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
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                Object openedKey = secure.getFileAttributeView(BasicFileAttributeView.class)
                        .readAttributes().fileKey();
                if (rootKey != null && !rootKey.equals(openedKey)) {
                    throw new IllegalArgumentException("Workspace root has changed");
                }
                moveRelative(secure, root.relativize(source), root.relativize(target));
                return;
            }
        }
        // Windows: MoveFileEx with MOVEFILE_REPLACE_EXISTING. A rename replaces the target
        // entry itself, so a link substituted at the target is replaced, never followed.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
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
