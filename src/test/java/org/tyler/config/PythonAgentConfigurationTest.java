package org.tyler.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tyler.controller.agent.AgentController;
import org.tyler.service.agent.AgentService;
import org.tyler.service.agent.IAgentService;
import org.tyler.service.agent.IClientFactory;
import org.tyler.service.agent.python.CoordinatedChatHistoryService;
import org.tyler.service.agent.python.PythonAgentService;
import org.tyler.service.agent.python.PythonWorker;
import org.tyler.service.agent.python.WorkerTestSupport;
import org.tyler.service.chatHistory.ChatHistoryService;
import org.tyler.service.chatHistory.IChatHistoryService;
import org.tyler.service.apiKey.IApiKeyService;
import org.tyler.service.agent.python.IOperationDispatcher;
import org.tyler.service.agent.bridge.ServiceOperationDispatcher;
import org.tyler.service.food.IFoodService;
import org.tyler.service.workout.IWorkoutPlanService;
import org.tyler.service.userInfo.IUserInfoService;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonAgentConfigurationTest {
    private final IApiKeyService keys = mock(IApiKeyService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PythonAgentConfiguration.class, AgentService.class, AgentController.class)
            .withBean(IClientFactory.class, () -> mock(IClientFactory.class))
            .withBean(IApiKeyService.class, () -> keys)
            .withBean(IFoodService.class, () -> mock(IFoodService.class))
            .withBean(IWorkoutPlanService.class, () -> mock(IWorkoutPlanService.class))
            .withBean(IUserInfoService.class, () -> mock(IUserInfoService.class))
            .withBean(IFileSandboxRead.class, () -> mock(IFileSandboxRead.class))
            .withBean(IFileSandboxWrite.class, () -> mock(IFileSandboxWrite.class))
            .withBean(ChatHistoryService.class, () -> mock(ChatHistoryService.class));

    @Test
    void defaultAndExplicitJavaSelectOnlyLegacyServiceWithoutAWorker() {
        for (String[] properties : List.of(new String[]{}, new String[]{"agent.implementation=java"})) {
            runner.withPropertyValues(properties).run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(1, context.getBeansOfType(IAgentService.class).size());
                assertInstanceOf(AgentService.class, context.getBean(IAgentService.class));
                assertTrue(context.getBeansOfType(PythonWorker.class).isEmpty());
                verifyNoInteractions(keys);
            });
        }
    }

    @Test
    void pythonSelectionWaitsForHandshakeWithoutReadingKey() {
        runner.withPropertyValues(pythonProperties("complete")).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(IAgentService.class).size());
            assertInstanceOf(PythonAgentService.class, context.getBean(IAgentService.class));
            assertInstanceOf(CoordinatedChatHistoryService.class, context.getBean(IChatHistoryService.class));
            assertTrue(context.getBean(PythonWorker.class).pid() > 0);
            assertEquals(1, context.getBeansOfType(IOperationDispatcher.class).size());
            var dispatcher = context.getBean(IOperationDispatcher.class);
            assertInstanceOf(ServiceOperationDispatcher.class, dispatcher);
            dispatcher.dispatch("file.write", WorkerTestSupport.fixture("file_write_request").path("payload").path("input"));
            verify(context.getBean(IFileSandboxWrite.class)).write(eq("notes/session.txt"), anyString());
            verifyNoInteractions(keys);
        });
    }

    @Test
    void invalidSelectionAndProtocolMismatchFailStartupRatherThanFallingBack() {
        runner.withPropertyValues("agent.implementation=unknown").run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues(pythonProperties("mismatch")).run(context -> assertNotNull(context.getStartupFailure()));
    }

    private String[] pythonProperties(String mode) {
        List<String> result = new ArrayList<>(List.of("agent.implementation=python"));
        List<String> command = WorkerTestSupport.settings(mode).command();
        for (int index = 0; index < command.size(); index++) result.add("agent.python.command[" + index + "]=" + command.get(index));
        return result.toArray(String[]::new);
    }
}
