package org.tyler.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.filesandbox.exceptions.FileReadException;
import org.tyler.filesandbox.FileSandBoxReadOnly;

import java.util.List;
import java.util.Map;

/**
 * 让 LLM 读取工作区内的文件。路径限制在沙箱目录内。
 */
@Component
public class ReadFileTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileSandBoxReadOnly sandbox;

    public ReadFileTool(FileSandBoxReadOnly sandbox) {
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
            throw new FileReadException("解析参数失败：" + e.getMessage(), e);
        }
        JsonNode pathNode = node.get("path");
        if (pathNode == null || pathNode.isNull() || pathNode.asText().isBlank()) {
            throw new FileReadException("缺少参数 path");
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