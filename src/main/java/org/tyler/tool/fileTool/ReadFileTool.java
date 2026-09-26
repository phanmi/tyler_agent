package org.tyler.tool.fileTool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.tool.ITool;

import java.util.List;
import java.util.Map;

/**
 * Lets the model read files within the sandbox workspace.
 */
@Component
public class ReadFileTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IFileSandboxRead sandbox;

    public ReadFileTool(IFileSandboxRead sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public String name() {
        return "readFile";
    }

    @Override
    public String description() {
        return "Read the content of a file inside the agent workspace. "
                + "Use this to load previously saved text, such as the user's prompts, "
                + "the assistant's responses, notes, or any other stored content. "
                + "The 'path' parameter is relative to the workspace root.";
    }

    @Override
    public String execute(String argumentsJson) {
        JsonNode node;
        try {
            node = MAPPER.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new FileReadException("Failed to parse arguments: " + e.getMessage(), e);
        }
        JsonNode pathNode = node.get("path");
        if (pathNode == null || pathNode.isNull() || pathNode.asText().isBlank()) {
            throw new FileReadException("Missing argument: path");
        }
        return sandbox.read(pathNode.asText());
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
                                                                "File path relative to the workspace root, e.g. prompts/2026-09-07.txt"))))
                                .putAdditionalProperty("required", JsonValue.from(List.of("path")))
                                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                                .build())
                .strict(true)
                .build();
    }
}