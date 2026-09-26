package org.tyler.filesandbox;

/**
 * Read-only file sandbox contract.
 *
 * <p>Exposes only {@code exists} and {@code read}.
 * Inject this interface when a caller needs no write access.
 */
public interface IFileSandboxRead {

    /** Checks for a sandbox file; blank or out-of-bounds paths are rejected. */
    boolean exists(String relativePath);

    /** Reads a sandbox file; blank or out-of-bounds paths are rejected. */
    String read(String relativePath);
}
