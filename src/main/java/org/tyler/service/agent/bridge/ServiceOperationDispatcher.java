package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tyler.service.agent.protocol.MessageValidator;
import org.tyler.service.agent.python.IOperationDispatcher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Validates protocol input and routes it through an immutable, complete handler registry. */
public final class ServiceOperationDispatcher implements IOperationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(ServiceOperationDispatcher.class);
    private final MessageValidator validator;
    private final Map<String, IOperationHandler> handlers;

    /** Invalid or incomplete registrations fail during construction, before any operation can run. */
    public ServiceOperationDispatcher(List<IOperationHandler> handlers, MessageValidator validator) {
        this.validator = validator;
        this.handlers = registerHandlers(handlers);
    }

    @Override
    public JsonNode dispatch(String operation, JsonNode input) {
        validator.validateOperationInput(operation, input);
        JsonNode result = handlers.get(operation).execute(input);
        // Failure causes propagate to the supervisor; payloads and paths never enter this log.
        log.debug("Agent service operation completed: operation={}", operation);
        return result;
    }

    private Map<String, IOperationHandler> registerHandlers(List<IOperationHandler> candidates) {
        Map<String, IOperationHandler> registered = new HashMap<>();
        for (IOperationHandler handler : candidates) {
            register(registered, handler);
        }
        var missing = new TreeSet<>(validator.supportedOperations());
        missing.removeAll(registered.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing agent operation handlers: " + missing);
        }
        return Map.copyOf(registered);
    }

    private void register(Map<String, IOperationHandler> registered, IOperationHandler handler) {
        String name = handler == null ? null : handler.operationName();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("Agent operation handler must declare a nonblank name");
        }
        if (!validator.supportedOperations().contains(name)) {
            throw new IllegalStateException("Unsupported agent operation handler: " + name);
        }
        if (registered.putIfAbsent(name, handler) != null) {
            throw new IllegalStateException("Duplicate agent operation handler: " + name);
        }
    }
}
