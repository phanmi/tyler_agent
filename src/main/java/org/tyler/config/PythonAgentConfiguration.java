package org.tyler.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.tyler.service.agent.python.ConversationLane;
import org.tyler.service.agent.python.CoordinatedChatHistoryService;
import org.tyler.service.agent.python.IOperationDispatcher;
import org.tyler.service.agent.python.PythonAgentService;
import org.tyler.service.agent.python.PythonAgentSettings;
import org.tyler.service.agent.python.PythonWorker;
import org.tyler.service.chatHistory.ChatHistoryService;
import org.tyler.service.chatHistory.IChatHistoryService;
import org.tyler.service.llm.ILlmKeyService;
import org.tyler.service.llm.ILlmSettingsService;

import java.time.Duration;
import java.util.List;

/** Wires the Python worker, shared conversation lane, and Java-owned application services. */
@Configuration(proxyBeanMethods = false)
@Import(OperationBridgeConfiguration.class)
public class PythonAgentConfiguration {
    @Bean
    public PythonAgentSettings pythonAgentSettings(Environment environment) {
        List<String> command = Binder.get(environment).bind("agent.python.command", Bindable.listOf(String.class))
                .orElse(List.of("agent-python/dist/tyler-agent-worker/tyler-agent-worker"
                        + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "")));
        PythonAgentSettings defaults = PythonAgentSettings.defaults(command);
        Duration run = Binder.get(environment).bind("agent.python.run-timeout", Duration.class).orElse(defaults.runTimeout());
        Duration operation = Binder.get(environment).bind("agent.python.operation-timeout", Duration.class).orElse(defaults.operationTimeout());
        return new PythonAgentSettings(command, defaults.startupTimeout(), defaults.queueTimeout(), run, operation,
                defaults.shutdownGrace(), defaults.queueCapacity());
    }

    @Bean(destroyMethod = "close")
    public ConversationLane conversationLane(PythonAgentSettings settings) { return new ConversationLane(settings); }

    @Bean(initMethod = "start", destroyMethod = "close")
    public PythonWorker pythonWorker(PythonAgentSettings settings, IOperationDispatcher dispatcher) {
        return new PythonWorker(settings, dispatcher);
    }

    @Bean
    @Primary
    public CoordinatedChatHistoryService coordinatedHistory(ChatHistoryService history, ConversationLane lane) {
        return new CoordinatedChatHistoryService(history, lane);
    }

    @Bean
    public PythonAgentService pythonAgentService(PythonWorker worker, ConversationLane lane,
                                                ILlmSettingsService llmSettings, ILlmKeyService keys,
                                                IChatHistoryService history, PythonAgentSettings settings) {
        // The provider and model come from the user's saved LLM settings on every request.
        return new PythonAgentService(worker, lane, llmSettings, keys, history, settings);
    }
}
