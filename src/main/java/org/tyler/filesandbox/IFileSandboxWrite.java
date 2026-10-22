package org.tyler.filesandbox;

/**
 * Write-only file sandbox contract.
 *
 * <p>Exposes only {@code exists} and {@code write}.
 * Inject this interface when a caller needs no read access.
 */
public interface IFileSandboxWrite {

    /** Returns false for missing paths; rejects traversal and linked/special components. */
    boolean exists(String relativePath);

    /**
     * Replaces UTF-8 text and creates checked parent directories as needed.
     * Invalid paths and pre-existing links raise IllegalArgumentException before writing;
     * I/O failures raise FileWriteException with a safe message. See FILE_SANDBOX.md
     * for limitations when another process concurrently changes directories.
     */
    void write(String relativePath, String content);
}
