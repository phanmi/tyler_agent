package org.tyler.service.agent;

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
import org.tyler.service.chatHistory.IChatHistoryService;
import org.tyler.tool.ITool;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentService implements IAgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** Limits tool rounds to prevent an endless tool-call loop. */
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
        // The factory reuses or creates the client and validates the API key.
        OpenAIClient client = clientFactory.getClient();

        // Combine recent history and the current message to preserve conversation context.
        List<ResponseInputItem> inputItems = buildHistoryInput(message);

        log.debug("Calling OpenAI with message: {} ({} history entries)", message, Math.max(0, inputItems.size() - 1));
        long start = System.currentTimeMillis();
        try {
            Response response = client.responses().create(createParamsWithInput(inputItems));
            log.info("Initial OpenAI call completed: model={}, elapsed {} ms", model, System.currentTimeMillis() - start);

            int rounds = 0;
            while (hasFunctionCall(response) && rounds < MAX_TOOL_ROUNDS) {
                long roundStart = System.currentTimeMillis();
                response = client.responses().create(submitToolOutputsParams(response));
                rounds++;
                log.info("OpenAI tool round {} completed in {} ms", rounds, System.currentTimeMillis() - roundStart);
            }
            String reply = extractText(response);
            log.debug("Final OpenAI reply: {}", reply);

            // Save the user message and assistant reply together after receiving the reply,
            // avoiding an incomplete exchange if one of two separate writes were to fail.
            chatHistoryService.appendExchange(message, reply);
            return reply;
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new OpenAIKeyException("This API key is invalid or unavailable", e);
        }
    }

    /** Sends the complete recent history and current message in the initial request. */
    private ResponseCreateParams createParamsWithInput(List<ResponseInputItem> input) {
        return ResponseCreateParams.builder()
                .model(model)
                .tools(toOpenAiTools())
                .input(ResponseCreateParams.Input.ofResponse(input))
                .build();
    }

    /** Converts saved history to OpenAI input items and appends the current user message. */
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

    /** Continues a response with previousResponseId and this round's function outputs. */
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

    /** Executes all tool calls in a response and builds the next request. */
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
                log.debug("Executing tool {} with arguments: {}", name, argumentsJson);
                long start = System.currentTimeMillis();
                String result = tool.execute(argumentsJson);
                long elapsed = System.currentTimeMillis() - start;
                log.info("Tool {} completed in {} ms", name, elapsed);
                log.debug("Tool {} result: {}", name, result);
                return result;
            }
        }
        throw new IllegalStateException("Unknown tool: " + name);
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
