package org.tyler.service.agent.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.tyler.controller.agent.AgentController;
import org.tyler.exceptionHandler.GenericExceptionHandler;
import org.tyler.exceptionHandler.ApiKeyExceptionHandler;
import org.tyler.exceptionHandler.exception.ApiKeyMissingException;
import org.tyler.model.chat.ChatMessage;
import org.tyler.exceptionHandler.exception.FileReadException;
import org.tyler.model.llm.LlmProvider;
import org.tyler.model.llm.LlmSettings;
import org.tyler.service.llm.ILlmKeyService;
import org.tyler.service.llm.ILlmSettingsService;
import org.tyler.service.chatHistory.IChatHistoryService;
import org.tyler.service.chatHistory.ChatHistoryService;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.tyler.service.agent.python.WorkerTestSupport.*;

class PythonAgentServiceTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 3, 20})
    void historySentToPythonUsesTheSharedConfiguredLimit(int maxMessages) throws Exception {
        var reader = mock(IFileSandboxRead.class);
        var writer = mock(IFileSandboxWrite.class);
        List<ChatMessage> saved = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            saved.add(new ChatMessage(i % 2 == 0 ? "user" : "assistant", "message-" + i));
        }
        when(reader.exists("history.json")).thenReturn(true);
        when(reader.read("history.json")).thenReturn(new ObjectMapper().writeValueAsString(saved));
        var store = new ChatHistoryService(reader, writer, "history.json", maxMessages);
        var keys = mock(ILlmKeyService.class);
        when(keys.get(LlmProvider.OPENAI)).thenReturn("SYNTHETIC");
        var worker = mock(PythonWorker.class);
        int expectedSize = maxMessages - maxMessages % 2;
        when(worker.execute(any())).thenAnswer(call -> {
            var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(0);
            // Default settings: OpenAI is active on its default model.
            assertEquals("openai", run.path("provider").asText());
            assertEquals("gpt-6-sol", run.path("model").asText());
            var snapshot = run.path("history");
            assertEquals(expectedSize, snapshot.size());
            for (int i = 0; i < expectedSize; i++) {
                assertEquals("message-" + (30 - expectedSize + i), snapshot.get(i).path("content").asText());
                assertEquals(i % 2 == 0 ? "user" : "assistant", snapshot.get(i).path("role").asText());
            }
            return "reply";
        });
        var settings = settings("complete");
        try (var lane = new ConversationLane(settings)) {
            var history = new CoordinatedChatHistoryService(store, lane);
            var service = new PythonAgentService(worker, lane, defaults(), keys, history, settings);
            assertEquals("reply", service.ask("question"));
            verify(worker).historySettled(true);
            verify(writer).write(eq("history.json"), anyString());
        }
    }

    @Test
    void currentKeysAndHistoryAreReadAfterQueueWaitAndHistoryClearIsOrdered() throws Exception {
        var settings = settings("complete");
        ILlmKeyService keys = mock(ILlmKeyService.class);
        when(keys.get(LlmProvider.OPENAI)).thenReturn("SYNTHETIC_A");
        CountDownLatch appendEntered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<ChatMessage> saved = new ArrayList<>();
        IChatHistoryService store = mock(IChatHistoryService.class);
        when(store.get()).thenAnswer(call -> List.copyOf(saved));
        when(store.appendExchange(anyString(), anyString())).thenAnswer(call -> {
            if (saved.isEmpty()) { appendEntered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
            saved.add(new ChatMessage("user", call.getArgument(0))); saved.add(new ChatMessage("assistant", call.getArgument(1)));
            return List.copyOf(saved);
        });
        when(store.clear()).thenAnswer(call -> { saved.clear(); return List.of(); });
        try (var lane = new ConversationLane(settings); var worker = new PythonWorker(settings, (name, input) -> null)) {
            var history = new CoordinatedChatHistoryService(store, lane);
            var service = new PythonAgentService(worker, lane, defaults(), keys, history, settings);
            var first = CompletableFuture.supplyAsync(() -> service.ask("first"));
            assertTrue(appendEntered.await(10, TimeUnit.SECONDS));
            var second = CompletableFuture.supplyAsync(() -> service.ask("second"));
            await(() -> lane.queuedRequests() == 1);
            when(keys.get(LlmProvider.OPENAI)).thenReturn("SYNTHETIC_B");
            release.countDown();
            assertEquals("first:0:SYNTHETIC_A", first.get(5, TimeUnit.SECONDS));
            assertEquals("second:2:SYNTHETIC_B", second.get(5, TimeUnit.SECONDS));
            assertEquals(4, history.get().size());
            assertTrue(history.clear().isEmpty());
            assertTrue(history.get().isEmpty());
            verify(store, times(2)).appendExchange(anyString(), anyString());
        } finally { release.countDown(); }
    }

    @Test
    void clearDuringAcceptedCompletionRunsAfterItsHistoryAppend() throws Exception {
        var settings = settings("complete");
        var keys = mock(ILlmKeyService.class); when(keys.get(LlmProvider.OPENAI)).thenReturn("SYNTHETIC");
        var store = mock(IChatHistoryService.class); when(store.get()).thenReturn(List.of());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(store.appendExchange(anyString(), anyString())).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return List.of();
        });
        try (var lane = new ConversationLane(settings); var worker = new PythonWorker(settings, (name, input) -> null)) {
            var history = new CoordinatedChatHistoryService(store, lane);
            var service = new PythonAgentService(worker, lane, defaults(), keys, history, settings);
            var answer = CompletableFuture.supplyAsync(() -> service.ask("message"));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var cleared = CompletableFuture.supplyAsync(history::clear);
            await(() -> lane.queuedRequests() == 1);
            verify(store, never()).clear();
            release.countDown(); answer.get(3, TimeUnit.SECONDS); cleared.get(3, TimeUnit.SECONDS);
            var order = inOrder(store); order.verify(store).appendExchange(anyString(), anyString()); order.verify(store).clear();
        } finally { release.countDown(); }
    }

    @Test
    void missingKeyAndHistoryFailureDoNotAppendOrReplay() {
        var settings = settings("complete");
        var keys = mock(ILlmKeyService.class);
        var history = mock(IChatHistoryService.class); when(history.get()).thenReturn(List.of());
        try (var lane = new ConversationLane(settings); var worker = new PythonWorker(settings, (name, input) -> null)) {
            var service = new PythonAgentService(worker, lane, defaults(), keys, history, settings);
            assertThrows(ApiKeyMissingException.class, () -> service.ask("missing"));
            assertEquals(-1, worker.pid());
            verifyNoInteractions(history);
            when(keys.get(LlmProvider.OPENAI)).thenReturn("SYNTHETIC");
            when(history.appendExchange(anyString(), anyString())).thenThrow(new IllegalStateException("history unavailable"));
            assertThrows(IllegalStateException.class, () -> service.ask("message"));
            verify(history, times(1)).appendExchange(anyString(), anyString());
        }
    }

    @ParameterizedTest
    @CsvSource({"failed_provider, IllegalStateException", "failed_authentication, ApiKeyMissingException",
            "failed_storage, SQLReadException", "crash, ProtocolException"})
    void failureCategoriesDoNotWriteHistoryOrFallback(String mode, String type) {
        var settings = settings(mode);
        var keys = mock(ILlmKeyService.class); when(keys.get(LlmProvider.OPENAI)).thenReturn("SYNTHETIC");
        var history = mock(IChatHistoryService.class); when(history.get()).thenReturn(List.of());
        try (var lane = new ConversationLane(settings); var worker = new PythonWorker(settings, (name, input) -> null)) {
            var service = new PythonAgentService(worker, lane, defaults(), keys, history, settings);
            RuntimeException error = assertThrows(RuntimeException.class, () -> service.ask("message"));
            assertEquals(type, error.getClass().getSimpleName());
            verify(history, never()).appendExchange(anyString(), anyString());
        }
    }

    @Test
    void httpChatHistoryAndClearContractsRemainCompatible() throws Exception {
        var settings = settings("complete");
        var keys = mock(ILlmKeyService.class); when(keys.get(LlmProvider.OPENAI)).thenReturn("SYNTHETIC");
        var history = mock(IChatHistoryService.class); when(history.get()).thenReturn(List.of());
        try (var lane = new ConversationLane(settings); var worker = new PythonWorker(settings, (name, input) -> null)) {
            var service = new PythonAgentService(worker, lane, defaults(), keys, history, settings);
            var mvc = MockMvcBuilders.standaloneSetup(new AgentController(service, history))
                    .setControllerAdvice(new GenericExceptionHandler(), new ApiKeyExceptionHandler()).build();
            mvc.perform(post("/api/agent/chat").contentType("application/json").content("{\"message\":\" hello \"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("hello:0:SYNTHETIC"));
            mvc.perform(get("/api/agent/history")).andExpect(status().isOk()).andExpect(content().json("[]"));
            mvc.perform(delete("/api/agent/history")).andExpect(status().isOk()).andExpect(content().json("[]"));
            when(keys.get(LlmProvider.OPENAI)).thenReturn("");
            mvc.perform(post("/api/agent/chat").contentType("application/json").content("{\"message\":\"hi\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @CsvSource({"openai, gpt-5.6", "anthropic, claude-opus-5-5", "deepseek, deepseek-v4-pro"})
    void theRunUsesTheActiveProviderItsModelAndOnlyItsKey(String id, String model) {
        LlmProvider active = LlmProvider.fromId(id);
        var keys = mock(ILlmKeyService.class);
        for (LlmProvider provider : LlmProvider.values()) {
            when(keys.get(provider)).thenReturn("SYNTHETIC_" + provider.id());
        }
        var history = mock(IChatHistoryService.class); when(history.get()).thenReturn(List.of());
        var worker = mock(PythonWorker.class);
        when(worker.execute(any())).thenAnswer(call -> {
            var run = (com.fasterxml.jackson.databind.JsonNode) call.getArgument(0);
            assertEquals(id, run.path("provider").asText());
            assertEquals(model, run.path("model").asText());
            assertEquals("SYNTHETIC_" + id, run.path("apiKey").asText());
            return "reply";
        });
        var settings = settings("complete");
        try (var lane = new ConversationLane(settings)) {
            var service = new PythonAgentService(worker, lane, selected(active, model), keys, history, settings);
            assertEquals("reply", service.ask("question"));
        }
        verify(keys, never()).get(argThat(provider -> provider != active));
    }

    @ParameterizedTest
    @CsvSource({"openai, OpenAI", "anthropic, Claude", "deepseek, DeepSeek"})
    void aMissingActiveKeyNamesTheProviderBeforeTheWorkerOrHistory(String id, String name) {
        LlmProvider active = LlmProvider.fromId(id);
        var keys = mock(ILlmKeyService.class);
        // Other providers' keys are saved, but never substitute for the active one.
        for (LlmProvider provider : LlmProvider.values()) {
            when(keys.get(provider)).thenReturn(provider == active ? "  " : "SYNTHETIC_OTHER");
        }
        var history = mock(IChatHistoryService.class);
        var worker = mock(PythonWorker.class);
        var settings = settings("complete");
        try (var lane = new ConversationLane(settings)) {
            var service = new PythonAgentService(worker, lane, selected(active, active.defaultModel()), keys,
                    history, settings);
            var error = assertThrows(ApiKeyMissingException.class, () -> service.ask("question"));
            assertEquals(name + " API key is empty; chat is unavailable", error.getMessage());
        }
        verifyNoInteractions(worker, history);
    }

    @Test
    void settingsChangesApplyToTheNextRequest() {
        var keys = mock(ILlmKeyService.class);
        when(keys.get(any())).thenReturn("SYNTHETIC");
        var llm = mock(ILlmSettingsService.class);
        when(llm.get()).thenReturn(LlmSettings.defaults(),
                new LlmSettings(LlmProvider.ANTHROPIC, LlmSettings.defaults().models()));
        var history = mock(IChatHistoryService.class); when(history.get()).thenReturn(List.of());
        var worker = mock(PythonWorker.class);
        List<String> providers = new ArrayList<>();
        when(worker.execute(any())).thenAnswer(call -> {
            providers.add(((com.fasterxml.jackson.databind.JsonNode) call.getArgument(0)).path("provider").asText());
            return "reply";
        });
        var settings = settings("complete");
        try (var lane = new ConversationLane(settings)) {
            var service = new PythonAgentService(worker, lane, llm, keys, history, settings);
            service.ask("first");
            service.ask("second");
        }
        assertEquals(List.of("openai", "anthropic"), providers);
    }

    @Test
    void anUnreadableSettingsFileFailsBeforeKeysWorkerOrHistory() {
        var llm = mock(ILlmSettingsService.class);
        var invalid = new FileReadException("LLM settings file is invalid");
        when(llm.get()).thenThrow(invalid);
        var keys = mock(ILlmKeyService.class);
        var history = mock(IChatHistoryService.class);
        var worker = mock(PythonWorker.class);
        var settings = settings("complete");
        try (var lane = new ConversationLane(settings)) {
            var service = new PythonAgentService(worker, lane, llm, keys, history, settings);
            assertSame(invalid, assertThrows(FileReadException.class, () -> service.ask("question")));
        }
        verifyNoInteractions(keys, worker, history);
    }

    private static ILlmSettingsService defaults() {
        var llm = mock(ILlmSettingsService.class);
        when(llm.get()).thenReturn(LlmSettings.defaults());
        return llm;
    }

    private static ILlmSettingsService selected(LlmProvider active, String model) {
        var models = new java.util.EnumMap<>(LlmSettings.defaults().models());
        models.put(active, model);
        var llm = mock(ILlmSettingsService.class);
        when(llm.get()).thenReturn(new LlmSettings(active, models));
        return llm;
    }
}
