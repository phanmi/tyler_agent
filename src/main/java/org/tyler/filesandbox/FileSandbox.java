package org.tyler.filesandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    /** Publish attempts when another program briefly locks the target (Windows sharing). */
    private static final int PUBLISH_ATTEMPTS = 4;
    private static final long FIRST_RETRY_DELAY_MILLIS = 25;
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** Points in a replacement write where tests inject failures. */
    enum WriteStep { STAGED, PUBLISH, CLEANUP }

    /** Test hook for replacement writes; production uses {@link #NONE}. */
    @FunctionalInterface
    interface WriteFaults {
        WriteFaults NONE = step -> { };

        void at(WriteStep step) throws IOException;
    }

    private final Path configuredRoot;
    private final Path root;
    private final WriteFaults faults;
    // Windows cannot rename over a file with an open handle, so this process's reads
    // (shared) and publishing renames (exclusive) exclude each other. Fair, so steady
    // reading cannot starve a save. Other programs are covered by the publish retry.
    private final ReadWriteLock handles = new ReentrantReadWriteLock(true);
    // Replaced only when a root deleted while running is recreated by write().
    private volatile Object rootKey;

    @Autowired
    public FileSandbox(@Value("${agent.workspace-dir:}") String workspaceDir) throws IOException {
        this(workspaceDir, WriteFaults.NONE);
    }

    FileSandbox(String workspaceDir, WriteFaults faults) throws IOException {
        this.faults = Objects.requireNonNull(faults);
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
            handles.readLock().lock();
            try (var channel = SandboxFileChannels.open(root, target, rootKey,
                    Set.of(StandardOpenOption.READ, NOFOLLOW));
                 var reader = Channels.newReader(channel, StandardCharsets.UTF_8)) {
                inspect(target);
                StringWriter content = new StringWriter();
                reader.transferTo(content);
                log.debug("Workspace file read completed ({} characters)", content.getBuffer().length());
                return content.toString();
            } finally {
                handles.readLock().unlock();
            }
        } catch (IOException e) {
            throw new FileReadException("Unable to read workspace file", e);
        }
    }

    /**
     * Replaces the file by staging the encoded content in a hidden sibling, forcing it to
     * storage, and atomically renaming it over the target. Readers see the complete old or
     * new content, and this process's reads never block the rename; concurrent writers to
     * one path are last-rename-wins. On any failure the
     * staged file is removed, the previous content is untouched, and cleanup failures are
     * attached as suppressed exceptions. Unsupported atomic rename fails rather than
     * falling back. A process crash can leave a {@code .<name>.<random>.tmp} sibling.
     */
    @Override
    public void write(String relativePath, String content) {
        Objects.requireNonNull(content, "File content must not be null");
        try {
            // Encode before staging so unencodable text (e.g. an unpaired surrogate) fails early.
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content));
            Path target = checkedPath(relativePath);
            recreateMissingRoot();
            createParents(target.getParent());
            if (inspect(target) && Files.isDirectory(target, NOFOLLOW)) {
                throw new IOException("Workspace write target is a directory");
            }
            Path staged = target.resolveSibling("." + target.getFileName() + "."
                    + UUID.randomUUID().toString().substring(0, 12) + ".tmp");
            try {
                stage(staged, bytes);
                // Revalidate ancestors and target immediately before publishing.
                inspect(target);
                publish(staged, target);
            } catch (IOException | RuntimeException e) {
                removeStaged(staged, e);
                throw e;
            }
            log.debug("Workspace file write completed ({} characters)", content.length());
        } catch (IOException e) {
            throw new FileWriteException("Unable to write workspace file", e);
        }
    }

    private void stage(Path staged, ByteBuffer bytes) throws IOException {
        // A rename publishes a new file, so permissions are not inherited from the old one.
        // Create it owner-only where POSIX permissions exist so a save can never widen
        // access (e.g. to the API key); Windows inherits the workspace directory's ACL.
        FileAttribute<?>[] attributes = staged.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[]{OWNER_ONLY}
                : new FileAttribute<?>[0];
        try (var channel = SandboxFileChannels.open(root, staged, rootKey,
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, NOFOLLOW), attributes)) {
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            faults.at(WriteStep.STAGED);
            if (channel instanceof FileChannel file) {
                // Durability: contents reach storage before the rename publishes them.
                file.force(true);
            }
        }
    }

    /** Renames atomically, retrying briefly while another program locks the target. */
    private void publish(Path staged, Path target) throws IOException {
        long delay = FIRST_RETRY_DELAY_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                faults.at(WriteStep.PUBLISH);
                handles.writeLock().lock();
                try {
                    SandboxFileChannels.replace(root, staged, target, rootKey);
                } finally {
                    handles.writeLock().unlock();
                }
                return;
            } catch (AccessDeniedException e) {
                if (attempt == PUBLISH_ATTEMPTS) {
                    throw e;
                }
                log.debug("Workspace file publish was denied; retrying (attempt {} of {})",
                        attempt + 1, PUBLISH_ATTEMPTS);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException stopped = new InterruptedIOException("Interrupted before publishing");
                    stopped.addSuppressed(e);
                    throw stopped;
                }
                delay *= 2;
            }
        }
    }

    /** Best-effort removal; a cleanup failure never replaces the original failure. */
    private void removeStaged(Path staged, Exception original) {
        try {
            faults.at(WriteStep.CLEANUP);
            Files.deleteIfExists(staged);
        } catch (IOException cleanup) {
            original.addSuppressed(cleanup);
            log.warn("Unable to remove staged workspace file after a failed write", cleanup);
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

    /**
     * Checks every existing component, including ancestors of missing write targets.
     * Returns false when the target, an ancestor below the root, or the root itself is absent.
     * Links and special entries are rejected below the root only; the root and its ancestors
     * may be non-link reparse directories (such as cloud-sync folders), while redirection
     * through any component is still rejected by the real-path comparison.
     */
    private boolean inspect(Path target) throws IOException {
        Path current = root.getRoot();
        for (Path component : current.relativize(target)) {
            current = current.resolve(component);
            BasicFileAttributes entry;
            try {
                entry = attributes(current);
            } catch (NoSuchFileException e) {
                if (current.startsWith(root)) {
                    return false;
                }
                throw e;
            }
            if (current.startsWith(root) && !current.equals(root)) {
                rejectLink(entry);
            }
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

    /**
     * Recreates a workspace root that was deleted while the application runs, then adopts
     * the new directory's identity. Its ancestors must still exist; a root replaced by a link
     * or a file is rejected rather than adopted.
     */
    private synchronized void recreateMissingRoot() throws IOException {
        try {
            attributes(root);
            return;
        } catch (NoSuchFileException e) {
            // Deleted while running; recreate below.
        }
        try {
            Files.createDirectory(root);
        } catch (FileAlreadyExistsException e) {
            // Another writer recreated it. Validate before adopting it.
        }
        BasicFileAttributes entry = attributes(root);
        if (entry.isSymbolicLink() || !entry.isDirectory() || !root.toRealPath().equals(root)) {
            throw new IllegalArgumentException("Workspace root has changed");
        }
        rootKey = entry.fileKey();
        log.info("Recreated missing workspace root");
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
