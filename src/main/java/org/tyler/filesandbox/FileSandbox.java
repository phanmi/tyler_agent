package org.tyler.filesandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

/**
 * Confines file access to the real location of the configured workspace.
 * The configured root may itself be a link; links and special/reparse entries below
 * that root are rejected, even when their destination is inside the workspace.
 *
 * <p>The default is {@code {user.home}/AppData/Local/tyler_agent}; override with
 * {@code agent.workspace-dir} or {@code AGENT_WORKSPACE_DIR}. See docs/FILE_SANDBOX.md
 * for the path, missing-file and concurrent filesystem mutation contracts.
 */
@Component
public class FileSandbox implements IFileSandboxRead, IFileSandboxWrite, IFileSandboxPath {
    private static final Logger log = LoggerFactory.getLogger(FileSandbox.class);
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    private final Path configuredRoot;
    private final Path root;
    private final Object rootKey;

    public FileSandbox(@Value("${agent.workspace-dir:}") String workspaceDir) throws IOException {
        configuredRoot = resolveRoot(workspaceDir);
        Files.createDirectories(configuredRoot);
        root = configuredRoot.toRealPath();
        rootKey = attributes(root).fileKey();
        inspect(root);
    }

    private static Path resolveRoot(String workspaceDir) {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Path.of(workspaceDir).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), "AppData", "Local", "tyler_agent")
                .toAbsolutePath().normalize();
    }

    @Override
    public Path resolve(String relativePath) {
        try {
            return checkedPath(relativePath);
        } catch (IOException e) {
            throw new FileReadException("Unable to inspect workspace path", e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        try {
            Path target = checkedPath(relativePath);
            return inspect(target);
        } catch (IOException e) {
            throw new FileReadException("Unable to inspect workspace path", e);
        }
    }

    @Override
    public String read(String relativePath) {
        try {
            Path target = checkedPath(relativePath);
            try (var channel = SandboxFileChannels.open(root, target, rootKey,
                    Set.of(StandardOpenOption.READ, NOFOLLOW));
                 var reader = Channels.newReader(channel, StandardCharsets.UTF_8)) {
                inspect(target);
                StringWriter content = new StringWriter();
                reader.transferTo(content);
                log.debug("Workspace file read completed ({} characters)", content.getBuffer().length());
                return content.toString();
            }
        } catch (IOException e) {
            throw new FileReadException("Unable to read workspace file", e);
        }
    }

    @Override
    public void write(String relativePath, String content) {
        Objects.requireNonNull(content, "File content must not be null");
        try {
            Path target = checkedPath(relativePath);
            createParents(target.getParent());
            inspect(target);
            // Open without truncation, then recheck before modifying an existing file.
            try (var channel = SandboxFileChannels.open(root, target, rootKey,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, NOFOLLOW));
                 var writer = Channels.newWriter(channel, StandardCharsets.UTF_8)) {
                inspect(target);
                channel.truncate(0);
                writer.write(content);
            }
            log.debug("Workspace file write completed ({} characters)", content.length());
        } catch (IOException e) {
            throw new FileWriteException("Unable to write workspace file", e);
        }
    }

    private Path checkedPath(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("File path must not be blank");
        }
        Path target;
        try {
            target = configuredRoot.resolve(Path.of(value)).normalize();
            if (target.startsWith(configuredRoot)) {
                target = root.resolve(configuredRoot.relativize(target));
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("File path is invalid", e);
        }
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the workspace");
        }
        inspect(target);
        return target;
    }

    /** Checks every existing component, including ancestors of missing write targets. */
    private boolean inspect(Path target) throws IOException {
        Path current = root.getRoot();
        for (Path component : current.relativize(target)) {
            current = current.resolve(component);
            BasicFileAttributes entry;
            try {
                entry = attributes(current);
            } catch (NoSuchFileException e) {
                if (current.startsWith(root) && !current.equals(root)) {
                    return false;
                }
                throw e;
            }
            rejectLink(entry);
            if (!current.toRealPath().equals(current)) {
                throw new IllegalArgumentException("Workspace path redirects through a link");
            }
            if (current.equals(root) && (!entry.isDirectory()
                    || (rootKey != null && !rootKey.equals(entry.fileKey())))) {
                throw new IllegalArgumentException("Workspace root has changed");
            }
        }
        return true;
    }

    private void createParents(Path parent) throws IOException {
        if (parent == null || !parent.startsWith(root)) {
            return;
        }
        Path current = root;
        for (Path component : root.relativize(parent)) {
            current = current.resolve(component);
            if (!inspect(current)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException e) {
                    // Another writer may have created it. Validate before using it.
                }
                inspect(current);
            }
        }
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
    }

    private static void rejectLink(BasicFileAttributes entry) {
        // On Windows, junctions and non-symlink reparse points are reported as "other".
        if (entry.isSymbolicLink() || entry.isOther()) {
            throw new IllegalArgumentException("Workspace links and special files are not allowed");
        }
    }
}
