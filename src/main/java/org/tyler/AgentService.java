package org.tyler;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentService {

    /** 防止模型反复调用工具导致死循环的兜底上限。 */
    private static final int MAX_TOOL_ROUNDS = 5;

    private final OpenAIClient client;
    private final String model;
    private final List<AgentTool> tools;

    public AgentService(OpenAIClient client,
                        @Value("${openai.model:gpt-5.6}") String model,
                        List<AgentTool> tools) {
        this.client = client;
        this.model = model;
        this.tools = tools;
    }

    public String ask(String message) {
        Response response = client.responses().create(createParams(message, null, null));

        int rounds = 0;
        while (hasFunctionCall(response) && rounds < MAX_TOOL_ROUNDS) {
            response = client.responses().create(submitToolOutputsParams(response));
            rounds++;
        }
        return extractText(response);
    }

    /** 首次调用 / 后续调用共用的参数构造。previousResponseId 与 input 二选一。 */
    private ResponseCreateParams createParams(String message,
                                              String previousResponseId,
                                              List<ResponseInputItem> input) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model)
                .tools(toOpenAiTools());

        if (previousResponseId != null) {
            builder.previousResponseId(previousResponseId);
            builder.input(ResponseCreateParams.Input.ofResponse(input));
        } else {
            builder.input(message);
        }
        return builder.build();
    }

    /** 把本次响应里的所有工具调用执行完，拼成下一次请求的参数。 */
    private ResponseCreateParams submitToolOutputsParams(Response response) {
        List<ResponseInputItem> outputs = new ArrayList<>();
        for (ResponseOutputItem item : response.output()) {
            if (item.isFunctionCall()) {
                ResponseFunctionToolCall call = item.asFunctionCall();
                String result = execute(call.name(), call.arguments());
                ResponseInputItem.FunctionCallOutput output = ResponseInputItem.FunctionCallOutput.builder()
                        .callId(call.callId())
                        .output(result)
                        .build();
                outputs.add(ResponseInputItem.ofFunctionCallOutput(output));
            }
        }
        return createParams(null, response.id(), outputs);
    }

    private String execute(String name, String argumentsJson) {
        for (AgentTool tool : tools) {
            if (tool.name().equals(name)) {
                return tool.execute(argumentsJson);
            }
        }
        throw new IllegalStateException("未知的工具：" + name);
    }

    private List<Tool> toOpenAiTools() {
        List<Tool> result = new ArrayList<>();
        for (AgentTool tool : tools) {
            FunctionTool functionTool = tool.toFunctionTool();
            result.add(Tool.ofFunction(functionTool));
        }
        return result;
    }

    private boolean hasFunctionCall(Response response) {
        for (ResponseOutputItem item : response.output()) {
            if (item.isFunctionCall()) {
                return true;
            }
        }
        return false;
    }

    private String extractText(Response response) {
        StringBuilder sb = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            item.message().ifPresent(msg -> {
                for (ResponseOutputMessage.Content content : msg.content()) {
                    content.outputText().ifPresent(t -> sb.append(t.text()));
                    content.refusal().ifPresent(r -> sb.append(r.refusal()));
                }
            });
        }
        return sb.toString();
    }
}
