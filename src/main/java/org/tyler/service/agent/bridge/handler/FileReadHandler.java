package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.service.agent.bridge.IOperationHandler;

/** Reads text through the existing sandbox; path checks and storage failures stay with it. */
@Component
@ConditionalOnProperty(name = "agent.implementation", havingValue = "python", matchIfMissing = true)
public final class FileReadHandler implements IOperationHandler {
    private final IFileSandboxRead reader;

    public FileReadHandler(IFileSandboxRead reader) {
        this.reader = reader;
    }

    @Override
    public String operationName() {
        return "file.read";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        return JsonNodeFactory.instance.objectNode().put("content", reader.read(input.get("path").textValue()));
    }
}
