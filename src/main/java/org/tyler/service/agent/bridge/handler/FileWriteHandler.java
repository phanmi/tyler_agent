package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.bridge.ProtectedWorkspaceFiles;

/** Writes through the sandbox and acknowledges the requested path only after successful completion. */
@Component
public final class FileWriteHandler implements IOperationHandler {
    private final IFileSandboxWrite writer;
    private final ProtectedWorkspaceFiles protectedFiles;

    public FileWriteHandler(IFileSandboxWrite writer, ProtectedWorkspaceFiles protectedFiles) {
        this.writer = writer;
        this.protectedFiles = protectedFiles;
    }

    @Override
    public String operationName() {
        return "file.write";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        String path = input.get("path").textValue();
        // The model may not replace keys, switch providers or rewrite saved history.
        protectedFiles.requireUnprotected(path);
        writer.write(path, input.get("content").textValue());
        return JsonNodeFactory.instance.objectNode().put("path", path);
    }
}
