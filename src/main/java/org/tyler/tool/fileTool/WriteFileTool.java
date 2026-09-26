package org.tyler.tool.fileTool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.tool.ITool;

import java.util.List;
import java.util.Map;

/**
 * Lets the model write text files within the sandbox workspace.
 */
@Component
public class WriteFileTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IFileSandboxWrite sandbox;

    public WriteFileTool(IFileSandboxWrite sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return "writeFile";
    }

    @Override
    public String description() {
        return "Write text content to a file inside the agent workspace, "
                + "creating parent directories as needed. "
                + "Use this to save the user's prompts, the assistant's responses, "
                + "notes, or any other text the user wants to persist. "
                + "Parameters: 'path' (relative to the workspace root) and 'content' (the text to save).";
    }

    @Override
    public String execute(String argumentsJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new FileWriteException("Failed to parse arguments: " + e.getMessage(), e);
        }
        JsonNode pathNode = node.get("path");
        JsonNode contentNode = node.get("content");
        if (pathNode == null || pathNode.isNull() || pathNode.asText().isBlank()) {
            throw new FileWriteException("Missing argument: path");
        }
        if (contentNode == null || contentNode.isNull()) {
            throw new FileWriteException("Missing argument: content");
        }
        sandbox.write(pathNode.asText(), contentNode.asText());
        return "Wrote " + pathNode.asText();
    }

    @Override
    public FunctionTool toFunctionTool() {
        return FunctionTool.builder()
                .name(name())
                .description(description())
                .parameters(
                        FunctionTool.Parameters.builder()
                                .putAdditionalProperty("type", JsonValue.from("object"))
                                .putAdditionalProperty(
                                        "properties",
                                        JsonValue.from(
                                                Map.of(
                                                        "path",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                "File path relative to the workspace root, e.g. prompts/2026-09-07.txt"),
                                                        "content",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                "The full text content to write to the file"))))
                                .putAdditionalProperty(
                                        "required", JsonValue.from(List.of("path", "content")))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                .strict(true)
                .build();
    }
}