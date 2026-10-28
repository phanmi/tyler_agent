package org.tyler.filesandbox;

/**
 * Read-only file sandbox contract.
 *
 * <p>Exposes only {@code exists} and {@code read}.
 * Inject this interface when a caller needs no write access.
 */
public interface IFileSandboxRead {

    /** Returns false for missing paths; rejects traversal and linked/special components. */
    boolean exists(String relativePath);

    /**
     * Reads UTF-8 text. Invalid or linked paths raise IllegalArgumentException;
     * missing files and other I/O failures raise FileReadException with a safe message.
     */
    String read(String relativePath);
}
