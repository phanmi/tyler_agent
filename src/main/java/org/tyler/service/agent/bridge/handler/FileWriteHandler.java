package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.stereotype.Component;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.service.agent.bridge.IOperationHandler;

/** Writes through the sandbox and acknowledges the requested path only after successful completion. */
@Component
public final class FileWriteHandler implements IOperationHandler {
    private final IFileSandboxWrite writer;

    public FileWriteHandler(IFileSandboxWrite writer) {
        this.writer = writer;
    }

    @Override
    public String operationName() {
        return "file.write";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        String path = input.get("path").textValue();
        writer.write(path, input.get("content").textValue());
        return JsonNodeFactory.instance.objectNode().put("path", path);
    }
}
