package org.tyler.filesandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.exceptionHandler.exception.WorkspacePathException;

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
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
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
    /**
     * Staged names stay within 255 UTF-8 bytes, which also fits NTFS's 255 UTF-16 units,
     * so a target name near the limit still gets a valid staged sibling.
     */
    private static final int MAX_NAME_BYTES = 255;
    private static final int LOCK_STRIPES = 64;

    /** Points in a replacement write where tests inject failures. */
    enum WriteStep { STAGED, PUBLISH, CLEANUP }

    /** Test hook for replacement writes; production uses {@link #NONE}. */
    @FunctionalInterface
    interface WriteFaults {
        WriteFaults NONE = step -> { };

        void at(WriteStep step) throws IOException;
    }

    /**
     * The root's file key where the provider supplies one; otherwise (Windows NIO) its
     * creation time, a best-effort identity that still detects a replaced directory.
     */
    private record RootIdentity(Object fileKey, FileTime created) {
        static RootIdentity of(BasicFileAttributes entry) {
            return new RootIdentity(entry.fileKey(), entry.creationTime());
        }

        boolean matches(BasicFileAttributes entry) {
            return fileKey != null ? fileKey.equals(entry.fileKey()) : created.equals(entry.creationTime());
        }
    }

    private final Path configuredRoot;
    private final Path root;
    private final WriteFaults faults;
    // Windows cannot rename over a file with an open handle, so this process's reads
    // (shared) and publishing renames (exclusive) of one path exclude each other. Locks
    // are striped by path so unrelated files rarely contend, and fair so steady reading
    // cannot starve a save. Other programs are covered by the publish retry.
    private final ReadWriteLock[] handles = new ReadWriteLock[LOCK_STRIPES];
    // Replaced only when a root deleted while running is recreated by write().
    private volatile RootIdentity rootIdentity;

    @Autowired
    public FileSandbox(@Value("${agent.workspace-dir:}") String workspaceDir) throws IOException {
        this(workspaceDir, WriteFaults.NONE);
    }

    FileSandbox(String workspaceDir, WriteFaults faults) throws IOException {
        this.faults = Objects.requireNonNull(faults);
        for (int i = 0; i < LOCK_STRIPES; i++) {
            handles[i] = new ReentrantReadWriteLock(true);
        }
        configuredRoot = resolveRoot(workspaceDir);
        Files.createDirectories(configuredRoot);
        root = configuredRoot.toRealPath();
        rootIdentity = RootIdentity.of(attributes(root));
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

    /**
     * Walks the target's components once, then checks only each sibling's own entry: the
     * siblings share the already validated parent, so their real paths need no new walk.
     */
    @Override
    public Path resolveWithSiblings(String relativePath, List<String> suffixes) {
        try {
            Path target = checkedPath(relativePath);
            Path parent = target.getParent();
            if (parent != null && Files.isDirectory(parent, NOFOLLOW)) {
                for (String suffix : suffixes) {
                    try {
                        rejectLink(attributes(target.resolveSibling(target.getFileName() + suffix)));
                    } catch (NoSuchFileException e) {
                        // An absent sibling is allowed.
                    }
                }
            }
            return target;
        } catch (IOException e) {
            throw new FileReadException("Unable to inspect workspace path", e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        try {
            return inspect(lexicalPath(relativePath));
        } catch (IOException e) {
            throw new FileReadException("Unable to inspect workspace path", e);
        }
    }

    @Override
    public String read(String relativePath) {
        try {
            Path target = checkedPath(relativePath);
            ReadWriteLock lock = handles(target);
            lock.readLock().lock();
            try (var channel = SandboxFileChannels.open(root, target, rootIdentity.fileKey(),
                    Set.of(StandardOpenOption.READ, NOFOLLOW));
                 var reader = Channels.newReader(channel, StandardCharsets.UTF_8)) {
                inspect(target);
                StringWriter content = new StringWriter();
                reader.transferTo(content);
                log.debug("Workspace file read completed ({} characters)", content.getBuffer().length());
                return content.toString();
            } finally {
                lock.readLock().unlock();
            }
        } catch (IOException e) {
            throw new FileReadException("Unable to read workspace file", e);
        }
    }

    /**
     * Replaces the file by staging the encoded content in a hidden sibling, forcing it to
     * storage, and atomically renaming it over the target. Readers see the complete old or
     * new content; this process's in-flight reads of the same file can delay the rename but
     * never fail it. Concurrent writers to one path are last-rename-wins. On any failure the
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
            // The target was just validated; it is inspected again before publishing.
            if (Files.isDirectory(target, NOFOLLOW)) {
                throw new IOException("Workspace write target is a directory");
            }
            Path staged = stagedSibling(target);
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

    /** {@code .<name>.<random>.tmp}, shortening {@code <name>} to keep within the name limit. */
    private static Path stagedSibling(Path target) {
        String suffix = "." + UUID.randomUUID().toString().substring(0, 12) + ".tmp";
        String name = target.getFileName().toString();
        int budget = MAX_NAME_BYTES - 1 - suffix.length();
        int end = 0;
        int used = 0;
        while (end < name.length()) {
            int codePoint = name.codePointAt(end);
            int size = codePoint < 0x80 ? 1 : codePoint < 0x800 ? 2 : codePoint < 0x10000 ? 3 : 4;
            if (used + size > budget) {
                break;
            }
            used += size;
            end += Character.charCount(codePoint);
        }
        return target.resolveSibling("." + name.substring(0, end) + suffix);
    }

    private void stage(Path staged, ByteBuffer bytes) throws IOException {
        // A rename publishes a new file, so permissions are not inherited from the old one.
        // Create it owner-only where POSIX permissions exist so a save can never widen
        // access (e.g. to the API key); Windows inherits the workspace directory's ACL.
        FileAttribute<?>[] attributes = staged.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? new FileAttribute<?>[]{OWNER_ONLY}
                : new FileAttribute<?>[0];
        try (var channel = SandboxFileChannels.open(root, staged, rootIdentity.fileKey(),
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
        ReadWriteLock lock = handles(target);
        long delay = FIRST_RETRY_DELAY_MILLIS;
        for (int attempt = 1; ; attempt++) {
            try {
                faults.at(WriteStep.PUBLISH);
                lock.writeLock().lock();
                try {
                    SandboxFileChannels.replace(root, staged, target, rootIdentity.fileKey());
                } finally {
                    lock.writeLock().unlock();
                }
                return;
            } catch (FileSystemException e) {
                if (!isTransientLock(e) || attempt == PUBLISH_ATTEMPTS) {
                    throw e;
                }
                log.debug("Workspace file publish was blocked; retrying (attempt {} of {})",
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

    /**
     * Windows reports a target held open by another program as access denied, or as a
     * sharing violation, which NIO raises as a plain {@link FileSystemException}. Specific
     * subtypes (missing file, unsupported atomic move, ...) are not transient.
     */
    private static boolean isTransientLock(FileSystemException e) {
        return e instanceof AccessDeniedException || e.getClass() == FileSystemException.class;
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

    private ReadWriteLock handles(Path target) {
        return handles[Math.floorMod(target.hashCode(), LOCK_STRIPES)];
    }

    private Path checkedPath(String value) throws IOException {
        Path target = lexicalPath(value);
        inspect(target);
        return target;
    }

    /** Maps the value into the real root without touching the filesystem. */
    private Path lexicalPath(String value) {
        if (value == null || value.isBlank()) {
            throw new WorkspacePathException("File path must not be blank");
        }
        Path target;
        try {
            target = configuredRoot.resolve(Path.of(value)).normalize();
            if (target.startsWith(configuredRoot)) {
                target = root.resolve(configuredRoot.relativize(target));
            }
        } catch (InvalidPathException e) {
            throw new WorkspacePathException("File path is invalid", e);
        }
        if (!target.startsWith(root)) {
            throw new WorkspacePathException("Path is outside the workspace");
        }
        return target;
    }

    /**
     * Checks every existing component, including ancestors of missing write targets.
     * Returns false when the target, an ancestor below the root, or the root itself is absent,
     * or when an ancestor is not a directory (POSIX would otherwise report ENOTDIR).
     * Links and special entries are rejected below the root only; the root and its ancestors
     * may be non-link reparse directories (such as cloud-sync folders), while redirection
     * through any component is still rejected by the real-path comparison.
     */
    private boolean inspect(Path target) throws IOException {
        Path current = root.getRoot();
        Path deepest = current;
        boolean present = true;
        for (Path component : current.relativize(target)) {
            current = current.resolve(component);
            BasicFileAttributes entry;
            try {
                entry = attributes(current);
            } catch (NoSuchFileException e) {
                if (!current.startsWith(root)) {
                    throw e;
                }
                present = false;
                break;
            }
            deepest = current;
            if (current.startsWith(root) && !current.equals(root)) {
                rejectLink(entry);
            }
            if (current.equals(root) && (!entry.isDirectory() || !rootIdentity.matches(entry))) {
                throw new WorkspacePathException("Workspace root has changed");
            }
            if (!entry.isDirectory() && !current.equals(target)) {
                // Nothing can exist below a file.
                present = false;
                break;
            }
        }
        // The deepest existing entry's real path differs from it if any component above it
        // redirects, so one comparison covers every ancestor.
        if (!deepest.toRealPath().equals(deepest)) {
            throw new WorkspacePathException("Workspace path redirects through a link");
        }
        return present;
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
            throw new WorkspacePathException("Workspace root has changed");
        }
        rootIdentity = RootIdentity.of(entry);
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
            throw new WorkspacePathException("Workspace links and special files are not allowed");
        }
    }
}
