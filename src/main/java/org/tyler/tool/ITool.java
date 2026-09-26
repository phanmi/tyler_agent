package org.tyler.tool;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;

import java.util.Map;

/**
 * A function tool available to the model.
 *
 * <p>Each tool provides:
 * <ul>
 *   <li>{@link #name()}: the function name exposed to the model;</li>
 *   <li>{@link #description()}: guidance about when to use the tool;</li>
 *   <li>{@link #execute(String)}: the implementation accepting JSON arguments.</li>
 * </ul>
 */
public interface ITool {

    String name();

    String description();

    /**
     * Executes the tool.
     *
     * @param argumentsJson JSON arguments from the model, usually "{}" for a tool with no parameters
     * @return result sent back to the model, usually plain text or JSON
     */
    String execute(String argumentsJson);

    /**
     * Describes this tool as a {@link FunctionTool} for the OpenAI Responses API.
     * Defaults to name and description; override to add a parameter schema.
     */
    default FunctionTool toFunctionTool() {
        return FunctionTool.builder()
                .name(name())
                .description(description())
                .parameters(
                        FunctionTool.Parameters.builder()
                                .putAdditionalProperty(
                                        "type",
                                        JsonValue.from("object")
                                )
                                .putAdditionalProperty(
                                        "properties",
                                        JsonValue.from(Map.of())
                                )
                                .putAdditionalProperty(
                                        "additionalProperties",
                                        JsonValue.from(false)
                                )
                                .build()
                )
                .strict(true)
                .build();
    }

}
