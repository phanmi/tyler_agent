package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.tyler.exceptionHandler.exception.OpenAIKeyException;
import org.tyler.service.agent.IAgentService;
import org.tyler.service.apiKey.IApiKeyService;
import org.tyler.service.chatHistory.IChatHistoryService;

/** Java owns request snapshots and durable history; Python owns all model/tool orchestration. */
public final class PythonAgentService implements IAgentService {
    private final PythonWorker worker;
    private final ConversationLane lane;
    private final IApiKeyService keys;
    private final IChatHistoryService history;
    private final PythonAgentSettings settings;
    private final String model;

    public PythonAgentService(PythonWorker worker, ConversationLane lane, IApiKeyService keys,
                              IChatHistoryService history, PythonAgentSettings settings, String model) {
        this.worker = worker;
        this.lane = lane;
        this.keys = keys;
        this.history = history;
        this.settings = settings;
        this.model = model;
    }

    @Override
    public String ask(String message) {
        return lane.call(() -> askOnLane(message));
    }

    private String askOnLane(String message) {
        String key = keys.get(); // Snapshot only when dequeued, so key edits affect waiting requests.
        if (key == null || key.isBlank()) throw new OpenAIKeyException("OpenAI API key is empty; chat is unavailable");
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("message", message)
                .put("model", model).put("apiKey", key).put("maxToolRounds", 5)
                .put("timeoutMs", settings.runTimeout().toMillis())
                .put("operationTimeoutMs", settings.operationTimeout().toMillis());
        var messages = payload.putArray("history");
        // The shared history service applies chat.max-messages (default 20) and removes
        // leading assistant messages. Reuse that configured bound for both agent paths.
        for (var item : history.get()) messages.addObject().put("role", item.role()).put("content", item.content());
        String reply;
        try { reply = worker.execute(payload); }
        finally { payload.remove("apiKey"); payload.remove("history"); }
        boolean saved = false;
        try {
            history.appendExchange(message, reply);
            saved = true;
            return reply;
        } finally {
            worker.historySettled(saved);
        }
    }
}
