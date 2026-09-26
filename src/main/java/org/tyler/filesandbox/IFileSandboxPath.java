package org.tyler.filesandbox;

import java.nio.file.Path;

/**
 * Contract for resolving sandbox paths.
 *
 * <p>Exposes {@code resolve} for callers such as SQLite DAOs that need an absolute path
 * without depending on the full file-access implementation.
 */
public interface IFileSandboxPath {

    /** Resolves an absolute sandbox path; blank or out-of-bounds paths are rejected. */
    Path resolve(String relativePath);
}