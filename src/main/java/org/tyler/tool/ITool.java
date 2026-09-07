package org.tyler.tool;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;

import java.util.Map;

/**
 * 一个可供 LLM 调用的工具（function tool）。
 *
 * <p>每个工具需要：
 * <ul>
 *   <li>{@link #name()} —— 暴露给模型的函数名；</li>
 *   <li>{@link #description()} —— 告诉模型什么时候该调用它；</li>
 *   <li>{@link #execute(String)} —— 真正干活的 Java 方法，入参是模型传来的 JSON 参数字符串。</li>
 * </ul>
 */
public interface ITool {

    String name();

    String description();

    /**
     * 执行工具逻辑。
     *
     * @param argumentsJson 模型生成的 JSON 参数字符串（无参工具通常是 "{}"）
     * @return 返回给模型的结果（通常是纯文本或 JSON 字符串）
     */
    String execute(String argumentsJson);

    /**
     * 把自己描述成 OpenAI Responses API 认识的 {@link FunctionTool}。
     * 默认只带 name + description；需要参数 schema 的工具可覆盖此方法补充 parameters。
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
