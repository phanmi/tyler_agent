package org.tyler.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import org.springframework.stereotype.Component;
import org.tyler.exceptionHandler.exception.FileWriteException;
import org.tyler.service.FileSandbox;

import java.util.List;
import java.util.Map;

/**
 * 让 LLM 把文本写入工作区内的文件。路径限制在沙箱目录内。
 */
@Component
public class WriteFileTool implements ITool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FileSandbox sandbox;

    public WriteFileTool(FileSandbox sandbox) {
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
            throw new FileWriteException("解析参数失败：" + e.getMessage(), e);
        }
        JsonNode pathNode = node.get("path");
        JsonNode contentNode = node.get("content");
        if (pathNode == null || pathNode.isNull() || pathNode.asText().isBlank()) {
            throw new FileWriteException("缺少参数 path");
        }
        if (contentNode == null || contentNode.isNull()) {
            throw new FileWriteException("缺少参数 content");
        }
        sandbox.write(pathNode.asText(), contentNode.asText());
        return "已写入 " + pathNode.asText();
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