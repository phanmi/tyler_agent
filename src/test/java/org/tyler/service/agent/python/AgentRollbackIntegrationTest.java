package org.tyler.service.agent.python;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.tyler.TylerAgentApplication;
import org.tyler.service.agent.AgentService;
import org.tyler.service.agent.IAgentService;
import org.tyler.service.agent.IClientFactory;
import org.tyler.service.chatHistory.IChatHistoryService;
import org.tyler.service.food.IFoodService;
import org.tyler.service.workout.IWorkoutService;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.python.PersistenceTestApplication.JSON;

/** Exercises configuration-and-restart rollback against the same durable data, without a live provider. */
class AgentRollbackIntegrationTest {
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartPreservesIdsAndHistoryWithoutReplayingCommittedWrites(boolean failAfterWrite) throws Exception {
        String food;
        String workouts;
        String history;
        try (var python = new PersistenceTestApplication(workspace)) {
            python.chat(failAfterWrite ? "food_then_failure" : "food", failAfterWrite ? 500 : 200);
            python.chat("plan", 200);
            food = python.get("/api/food?date=2026-10-19").toString();
            workouts = JSON.writeValueAsString(python.context.getBean(IWorkoutService.class).getAllWorkouts());
            history = python.get("/api/agent/history").toString();
            assertEquals(1, python.gate.foodCalls.get());
        }

        var application = new SpringApplication(TylerAgentApplication.class, LegacyProvider.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        try (var java = application.run("--agent.implementation=java", "--agent.workspace-dir=" + workspace,
                "--chat.max-messages=100", "--logging.level.root=ERROR", "--spring.main.banner-mode=off")) {
            assertEquals(1, java.getBeansOfType(IAgentService.class).size());
            assertInstanceOf(AgentService.class, java.getBean(IAgentService.class));
            assertTrue(java.getBeansOfType(PythonWorker.class).isEmpty());
            assertTrue(java.getBeansOfType(IOperationDispatcher.class).isEmpty());
            var foods = java.getBean(IFoodService.class);
            var plans = java.getBean(IWorkoutService.class);
            var histories = java.getBean(IChatHistoryService.class);
            var provider = java.getBean(ResponseService.class);
            verifyNoInteractions(provider); // Restart itself must not replay any conversation.
            assertEquals(food, JSON.writeValueAsString(foods.getFoodRecordsByDate("2026-10-19")));
            assertEquals(workouts, JSON.writeValueAsString(plans.getAllWorkouts()));
            assertEquals(history, JSON.writeValueAsString(histories.get()));
            assertEquals("", java.getBean(IAgentService.class).ask("New rollback request"));
            verify(provider, times(1)).create(any(ResponseCreateParams.class));
            assertEquals(JSON.readTree(history).size() + 2, histories.get().size());
            history = JSON.writeValueAsString(histories.get());
        }

        try (var python = new PersistenceTestApplication(workspace)) {
            assertEquals(food, python.get("/api/food?date=2026-10-19").toString());
            assertEquals(workouts, JSON.writeValueAsString(python.context.getBean(IWorkoutService.class).getAllWorkouts()));
            assertEquals(history, python.get("/api/agent/history").toString());
            assertEquals(0, python.gate.foodCalls.get());
            python.chat("reply", 200);
            assertEquals(JSON.readTree(history).size() + 2, python.get("/api/agent/history").size());
        }
    }

    /** Reuses the characterized empty-reply contract; the real legacy loop and history service run. */
    @TestConfiguration(proxyBeanMethods = false)
    static class LegacyProvider {
        @Bean
        ResponseService rollbackResponses() {
            var responses = mock(ResponseService.class);
            when(responses.create(any(ResponseCreateParams.class))).thenReturn(Response.builder()
                    .id("rollback-response").createdAt(0).model("test-model")
                    .error(Optional.empty()).incompleteDetails(Optional.empty()).instructions(Optional.empty())
                    .metadata(Optional.empty()).output(List.of()).parallelToolCalls(false)
                    .temperature(1.0).toolChoice(ToolChoiceOptions.AUTO).tools(List.of()).topP(1.0).build());
            return responses;
        }

        @Bean
        @Primary
        IClientFactory rollbackClientFactory(ResponseService responses) {
            var client = mock(OpenAIClient.class);
            when(client.responses()).thenReturn(responses);
            return () -> client;
        }
    }
}
