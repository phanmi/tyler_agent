package org.tyler.filesandbox;

/**
 * Write-only file sandbox contract.
 *
 * <p>Exposes only {@code exists} and {@code write}.
 * Inject this interface when a caller needs no read access.
 */
public interface IFileSandboxWrite {

    /** Checks for a sandbox file; blank or out-of-bounds paths are rejected. */
    boolean exists(String relativePath);

    /** Writes a sandbox file and creates parent directories as needed. */
    void write(String relativePath, String content);
}
