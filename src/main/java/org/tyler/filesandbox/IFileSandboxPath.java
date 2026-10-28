package org.tyler.filesandbox;

import java.nio.file.Path;

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
     * {@link IllegalArgumentException}; inspection failures raise FileReadException.
     * This validates a snapshot, not a persistent capability. Native consumers must
     * revalidate before opening and require trusted ownership of workspace directories.
     */
    Path resolve(String relativePath);
}
