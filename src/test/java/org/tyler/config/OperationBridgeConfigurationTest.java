package org.tyler.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.tyler.filesandbox.IApplicationOwnedFiles;
import org.tyler.filesandbox.IFileSandboxPath;
import org.tyler.filesandbox.IFileSandboxRead;
import org.tyler.filesandbox.IFileSandboxWrite;
import org.tyler.service.agent.bridge.IOperationHandler;
import org.tyler.service.agent.protocol.MessageValidator;
import org.tyler.service.agent.python.IOperationDispatcher;
import org.tyler.service.food.IFoodService;
import org.tyler.service.userInfo.IUserInfoService;
import org.tyler.service.workout.IWorkoutPlanService;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationBridgeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OperationBridgeConfiguration.class)
            .withBean(IFoodService.class, () -> mock(IFoodService.class))
            .withBean(IWorkoutPlanService.class, () -> mock(IWorkoutPlanService.class))
            .withBean(IUserInfoService.class, () -> mock(IUserInfoService.class))
            .withBean(IFileSandboxRead.class, () -> mock(IFileSandboxRead.class))
            .withBean(IFileSandboxWrite.class, () -> mock(IFileSandboxWrite.class))
            .withBean(IFileSandboxPath.class, () -> path -> Path.of("workspace").toAbsolutePath().resolve(path).normalize())
            .withBean(IApplicationOwnedFiles.class, () -> () -> List.of("apikey.txt"));

    @Test
    void discoversExactlyTheSchemaOperations() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var handlers = context.getBeansOfType(IOperationHandler.class).values();
            var allowed = context.getBean(MessageValidator.class).supportedOperations();
            assertEquals(allowed.size(), handlers.size());
            assertEquals(allowed, handlers.stream().map(IOperationHandler::operationName).collect(Collectors.toSet()));
            assertEquals(1, context.getBeansOfType(IOperationDispatcher.class).size());
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unapproved.operation", "file.read"})
    void invalidOrDuplicateHandlerStopsStartup(String name) {
        var extra = mock(IOperationHandler.class);
        when(extra.operationName()).thenReturn(name);
        runner
                .withBean("extraHandler", IOperationHandler.class, () -> extra)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    while (failure.getCause() != null) failure = failure.getCause();
                    assertInstanceOf(IllegalStateException.class, failure);
                    verify(extra, never()).execute(any());
                });
    }

    @Test
    void missingHandlerStopsStartupWithItsOperationName() {
        runner
                .withInitializer(context -> context.addBeanFactoryPostProcessor(factory ->
                        ((BeanDefinitionRegistry) factory).removeBeanDefinition("fileReadHandler")))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure);
                    while (failure.getCause() != null) failure = failure.getCause();
                    assertEquals("Missing agent operation handlers: [file.read]", failure.getMessage());
                });
    }
}
