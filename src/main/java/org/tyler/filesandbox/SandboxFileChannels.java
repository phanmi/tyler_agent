package org.tyler.filesandbox;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.Set;

/** Opens files relative to directory handles where the provider supports it. */
final class SandboxFileChannels {
    private SandboxFileChannels() {
    }

    static SeekableByteChannel open(Path root, Path target, Object rootKey,
            Set<? extends OpenOption> options) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            if (stream instanceof SecureDirectoryStream<Path> secure) {
                Object openedKey = secure.getFileAttributeView(BasicFileAttributeView.class)
                        .readAttributes().fileKey();
                if (rootKey != null && !rootKey.equals(openedKey)) {
                    throw new IllegalArgumentException("Workspace root has changed");
                }
                return openRelative(secure, root.relativize(target), options);
            }
            // Windows NIO has no SecureDirectoryStream. NOFOLLOW protects the leaf;
            // FileSandbox checks ancestors before and after opening (see policy doc).
            return Files.newByteChannel(target, options);
        }
    }

    private static SeekableByteChannel openRelative(SecureDirectoryStream<Path> directory,
            Path relative, Set<? extends OpenOption> options) throws IOException {
        if (relative.getNameCount() == 1) {
            return directory.newByteChannel(relative, options);
        }
        try (var child = directory.newDirectoryStream(relative.getName(0), LinkOption.NOFOLLOW_LINKS)) {
            return openRelative(child, relative.subpath(1, relative.getNameCount()), options);
        }
    }
}
