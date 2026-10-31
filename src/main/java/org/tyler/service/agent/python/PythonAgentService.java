package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;
import org.tyler.model.llm.LlmProvider;
import org.tyler.model.llm.LlmSettings;
import org.tyler.service.agent.IAgentService;
import org.tyler.service.chatHistory.IChatHistoryService;
import org.tyler.service.llm.ILlmKeyService;
import org.tyler.service.llm.ILlmSettingsService;

/** Java owns request snapshots and durable history; Python owns all model/tool orchestration. */
public final class PythonAgentService implements IAgentService {
    private final PythonWorker worker;
    private final ConversationLane lane;
    private final ILlmSettingsService llmSettings;
    private final ILlmKeyService keys;
    private final IChatHistoryService history;
    private final PythonAgentSettings settings;

    public PythonAgentService(PythonWorker worker, ConversationLane lane, ILlmSettingsService llmSettings,
                              ILlmKeyService keys, IChatHistoryService history, PythonAgentSettings settings) {
        this.worker = worker;
        this.lane = lane;
        this.llmSettings = llmSettings;
        this.keys = keys;
        this.history = history;
        this.settings = settings;
    }

    @Override
    public String ask(String message) {
        return lane.call(() -> askOnLane(message));
    }

    private String askOnLane(String message) {
        // Snapshot the provider, model, and key only when dequeued, so Settings changes made
        // while a request waits apply to it. An unreadable settings file fails here, before
        // the worker or history is touched.
        LlmSettings selected = llmSettings.get();
        LlmProvider provider = selected.activeProvider();
        String key = keys.get(provider);
        if (key == null || key.isBlank()) {
            // Only the active provider's key is used; another provider's key never substitutes.
            throw new ApiKeyMissingException(provider.displayName() + " API key is empty; chat is unavailable");
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("message", message)
                .put("provider", provider.id()).put("model", selected.model(provider)).put("apiKey", key)
                .put("maxToolRounds", 5)
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
