package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.bridge.ProtectedWorkspaceFiles;

/** Reads text through the existing sandbox; path checks and storage failures stay with it. */
@Component
public final class FileReadHandler implements IOperationHandler {
    private final IFileSandboxRead reader;
    private final ProtectedWorkspaceFiles protectedFiles;

    public FileReadHandler(IFileSandboxRead reader, ProtectedWorkspaceFiles protectedFiles) {
        this.reader = reader;
        this.protectedFiles = protectedFiles;
    }

    @Override
    public String operationName() {
        return "file.read";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        String path = input.get("path").textValue();
        // Keys, provider settings and chat history never reach the model or its provider.
        protectedFiles.requireUnprotected(path);
        return JsonNodeFactory.instance.objectNode().put("content", reader.read(path));
    }
}
