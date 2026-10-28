package org.tyler.exceptionHandler.exception;

/**
 * Thrown when the file sandbox rejects a path: blank, malformed, outside the workspace,
 * or reaching a link, special file or changed root.
 *
 * <p>It remains an {@link IllegalArgumentException}, so a request- or model-supplied path
 * is still a client error. Services whose path comes from configuration catch this type
 * (and only this type) to report the rejection as a storage failure.
 */
public class WorkspacePathException extends IllegalArgumentException {

    public WorkspacePathException(String message) {
        super(message);
    }

    public WorkspacePathException(String message, Throwable cause) {
        super(message, cause);
    }
}
