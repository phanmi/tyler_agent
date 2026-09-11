package org.tyler.service;

import com.openai.client.OpenAIClient;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.model.chat.ChatMessage;
import org.tyler.tool.ITool;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentService implements IAgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 防止模型反复调用工具导致死循环的兜底上限。 */
    private static final int MAX_TOOL_ROUNDS = 5;

    private final IClientFactory clientFactory;
    private final String model;
    private final List<ITool> tools;
    private final IChatHistoryService chatHistoryService;

    public AgentService(IClientFactory clientFactory,
                        @Value("${openai.model:gpt-5.6}") String model,
                        List<ITool> tools,
                        IChatHistoryService chatHistoryService) {
        this.clientFactory = clientFactory;
        this.model = model;
        this.tools = tools;
        this.chatHistoryService = chatHistoryService;
    }

    @Override
    public String ask(String message) {
        // 通过工厂获取（复用或按需创建的）client；空 key、建 client 的细节都交给工厂。
        OpenAIClient client = clientFactory.getClient();

        // 把「历史 + 当前消息」拼成完整 input，让模型记住前面的对话（跨轮记忆）。
        List<ResponseInputItem> inputItems = buildHistoryInput(message);

        log.debug("调用 OpenAI，用户消息：{}（携带历史 {} 条）", message, Math.max(0, inputItems.size() - 1));
        long start = System.currentTimeMillis();
        try {
            Response response = client.responses().create(createParamsWithInput(inputItems));
            log.info("OpenAI 首次调用完成，model={}，耗时 {} ms", model, System.currentTimeMillis() - start);

            int rounds = 0;
            while (hasFunctionCall(response) && rounds < MAX_TOOL_ROUNDS) {
                long roundStart = System.currentTimeMillis();
                response = client.responses().create(submitToolOutputsParams(response));
                rounds++;
                log.info("OpenAI 工具轮次 {} 完成，耗时 {} ms", rounds, System.currentTimeMillis() - roundStart);
            }
            String reply = extractText(response);
            log.debug("OpenAI 最终回复：{}", reply);

            // 拿到回复后再落盘：user + assistant 各一条。只有成功才写，避免失败污染历史。
            chatHistoryService.append("user", message);
            chatHistoryService.append("assistant", reply);
            return reply;
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new OpenAIKeyException("此 API Key 错误或不可用", e);
        }
    }

    /** 首次调用：把完整历史 + 当前消息作为 input 列表传入。 */
    private ResponseCreateParams createParamsWithInput(List<ResponseInputItem> input) {
        return ResponseCreateParams.builder()
                .model(model)
                .tools(toOpenAiTools())
                .input(ResponseCreateParams.Input.ofResponse(input))
                .build();
    }

    /** 读取历史并转成 OpenAI 的 input item 列表，末尾追加当前用户消息。 */
    private List<ResponseInputItem> buildHistoryInput(String currentMessage) {
        List<ResponseInputItem> items = new ArrayList<>();
        for (ChatMessage msg : chatHistoryService.get()) {
            items.add(toInputItem(msg.role(), msg.content()));
        }
        items.add(toInputItem("user", currentMessage));
        return items;
    }

    private ResponseInputItem toInputItem(String role, String content) {
        EasyInputMessage.Role openAiRole = "assistant".equals(role)
                ? EasyInputMessage.Role.ASSISTANT
                : EasyInputMessage.Role.USER;
        EasyInputMessage message = EasyInputMessage.builder()
                .role(openAiRole)
                .content(content)
                .build();
        return ResponseInputItem.ofEasyInputMessage(message);
    }

    /** 工具轮次调用：用 previousResponseId 续写，携带本轮的 function call output。 */
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
        for (ITool tool : tools) {
            if (tool.name().equals(name)) {
                log.debug("执行工具 {}，参数：{}", name, argumentsJson);
                long start = System.currentTimeMillis();
                String result = tool.execute(argumentsJson);
                long elapsed = System.currentTimeMillis() - start;
                log.info("工具 {} 执行完成，耗时 {} ms", name, elapsed);
                log.debug("工具 {} 结果：{}", name, result);
                return result;
            }
        }
        throw new IllegalStateException("未知的工具：" + name);
    }

    private List<Tool> toOpenAiTools() {
        List<Tool> result = new ArrayList<>();
        for (ITool tool : tools) {
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
