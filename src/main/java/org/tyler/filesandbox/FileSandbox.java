package org.tyler.filesandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.exceptionHandler.exception.FileWriteException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * File sandbox implementation supporting both reads and writes.
 *
 * <p>Restricts paths to a workspace directory to reject traversal outside it.
 *
 * <p>The default root is {@code {user.home}/AppData/Local/tyler_agent}.
 * Override it with {@code agent.workspace-dir} or {@code AGENT_WORKSPACE_DIR}.
 *
 * <p>Application file access, apart from logging, goes through this sandbox.
 * Callers inject {@link IFileSandboxRead} or {@link IFileSandboxWrite}
 * according to the capabilities they need.
 */
@Component
public class FileSandbox implements IFileSandboxRead, IFileSandboxWrite, IFileSandboxPath {

    private static final Logger log = LoggerFactory.getLogger(FileSandbox.class);

    private final Path root;

    public FileSandbox(@Value("${agent.workspace-dir:}") String workspaceDir) throws IOException {
        this.root = resolveRoot(workspaceDir);
        Files.createDirectories(root);
    }

    private static Path resolveRoot(String workspaceDir) {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Path.of(workspaceDir).toAbsolutePath().normalize();
        }
        String home = System.getProperty("user.home");
        return Path.of(home, "AppData", "Local", "tyler_agent").toAbsolutePath().normalize();
    }

    /** Returns the absolute sandbox root. */
    private Path root() {
        return root;
    }

    /** Resolves a relative path inside the sandbox and validates its boundaries. */
    @Override
    public Path resolve(String relativePath) {
        return resolveInside(relativePath);
    }

    /** Checks for a sandbox file; blank or out-of-bounds paths are rejected. */
    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolveInside(relativePath));
    }

    /** Reads a sandbox file; blank or out-of-bounds paths are rejected. */
    @Override
    public String read(String relativePath) {
        log.debug("Reading file: {}", relativePath);
        try {
            return Files.readString(resolveInside(relativePath));
        } catch (IOException e) {
            throw new FileReadException(e.getMessage(), e);
        }
    }

    /** Writes a sandbox file and creates parent directories as needed. */
    @Override
    public void write(String relativePath, String content) {
        log.debug("Writing file: {} ({} characters)", relativePath, content == null ? 0 : content.length());
        Path target = resolveInside(relativePath);
        Path parent = target.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new FileWriteException(e.getMessage(), e);
            }
        }
        try {
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new FileWriteException(e.getMessage(), e);
        }
    }

    /** Resolves a relative path and rejects paths outside the workspace. */
    private Path resolveInside(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("File path must not be blank");
        }
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the workspace: " + relativePath);
        }
        return resolved;
    }
}
