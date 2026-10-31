package org.tyler.filesandbox;

import java.util.List;

/**
 * Implemented by a service that keeps its own files in the workspace, such as provider keys,
 * provider settings or chat history. The agent's file tools refuse every path declared here,
 * so a new application-owned file is protected as soon as its service implements this.
 */
public interface IApplicationOwnedFiles {

    /** Workspace-relative paths of the files this service owns. */
    List<String> ownedPaths();
}
