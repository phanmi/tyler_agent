package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tyler.service.agent.protocol.MessageValidator;
import org.tyler.service.agent.protocol.ProtocolException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationRegistryTest {
    private final MessageValidator validator = new MessageValidator();

    private IOperationHandler handler(String name) {
        var handler = mock(IOperationHandler.class);
        when(handler.operationName()).thenReturn(name);
        return handler;
    }

    private List<IOperationHandler> completeRegistry() {
        return validator.supportedOperations().stream().map(this::handler).toList();
    }

    @Test
    void missingAndDuplicateRegistrationsFailBeforeExecution() {
        var handlers = new ArrayList<>(completeRegistry());
        String missing = handlers.removeFirst().operationName();
        assertTrue(assertThrowsExactly(IllegalStateException.class,
                () -> new ServiceOperationDispatcher(handlers, validator)).getMessage().contains(missing));
        var duplicates = new ArrayList<>(completeRegistry());
        duplicates.add(handler(duplicates.getFirst().operationName()));
        assertTrue(assertThrowsExactly(IllegalStateException.class,
                () -> new ServiceOperationDispatcher(duplicates, validator)).getMessage().contains("Duplicate"));
        duplicates.forEach(handler -> verify(handler, never()).execute(any()));
        assertThrowsExactly(IllegalStateException.class, () -> new ServiceOperationDispatcher(List.of(), validator));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "new.unapproved", "GetUserInfo"})
    void invalidRegistrationNamesFailStartup(String name) {
        var handlers = new ArrayList<>(completeRegistry());
        handlers.add(handler(name));
        assertThrowsExactly(IllegalStateException.class, () -> new ServiceOperationDispatcher(handlers, validator));
        handlers.forEach(handler -> verify(handler, never()).execute(any()));
    }

    @Test
    void registryCopiesInputAndValidatesBeforeCallingSelectedHandler() {
        var handlers = new ArrayList<>(completeRegistry());
        var selected = handlers.stream().filter(h -> h.operationName().equals("file.write")).findFirst().orElseThrow();
        var dispatcher = new ServiceOperationDispatcher(handlers, validator);
        handlers.clear();
        var input = JsonNodeFactory.instance.objectNode().put("path", "notes.txt").put("content", "text");
        var result = JsonNodeFactory.instance.objectNode().put("path", "notes.txt");
        when(selected.execute(input)).thenReturn(result);
        assertEquals("INVALID_MESSAGE", assertThrowsExactly(ProtocolException.class,
                () -> dispatcher.dispatch("file.write", JsonNodeFactory.instance.objectNode())).code());
        verify(selected, never()).execute(any());
        assertSame(result, dispatcher.dispatch("file.write", input));
        verify(selected).execute(input);
    }
}
