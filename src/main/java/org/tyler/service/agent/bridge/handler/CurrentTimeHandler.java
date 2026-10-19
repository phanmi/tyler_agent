package org.tyler.service.agent.bridge.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.tyler.service.agent.bridge.IOperationHandler;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Returns server-local time using an injected clock and the existing text representation. */
@Component
@ConditionalOnProperty(name = "agent.implementation", havingValue = "python")
public final class CurrentTimeHandler implements IOperationHandler {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");
    private final Clock clock;

    public CurrentTimeHandler(@Qualifier("agentOperationClock") Clock clock) {
        this.clock = clock;
    }

    @Override
    public String operationName() {
        return "time.current";
    }

    @Override
    public JsonNode execute(JsonNode input) {
        return JsonNodeFactory.instance.objectNode().put("text", FORMAT.format(ZonedDateTime.now(clock)));
    }
}
