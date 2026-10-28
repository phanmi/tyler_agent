package org.tyler.filesandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * Contract for resolving sandbox paths.
 *
 * <p>Exposes {@code resolve} for callers such as SQLite DAOs that need an absolute path
 * without depending on the full file-access implementation.
 */
public interface IFileSandboxPath {

    /**
     * Returns an absolute path under the real configured root, allowing missing targets.
     * Rejects blank, out-of-bounds and existing linked/special path components with
     * {@link org.tyler.exceptionHandler.exception.WorkspacePathException}; inspection
     * failures raise FileReadException.
     * This validates a snapshot, not a persistent capability. Native consumers must
     * revalidate before opening and require trusted ownership of workspace directories.
     */
    Path resolve(String relativePath);

    /**
     * Resolves {@code relativePath} like {@link #resolve(String)} and also validates the
     * siblings named by appending each suffix to its file name (such as SQLite sidecars).
     */
    default Path resolveWithSiblings(String relativePath, List<String> suffixes) {
        Path target = resolve(relativePath);
        for (String suffix : suffixes) {
            resolve(target + suffix);
        }
        return target;
    }
}
