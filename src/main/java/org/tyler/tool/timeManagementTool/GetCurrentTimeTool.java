package org.tyler.tool.timeManagementTool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.tyler.tool.ITool;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Returns the current date and time in the server's local time zone.
 */
@Component
@ConditionalOnProperty(name = "agent.implementation", havingValue = "java")
public class GetCurrentTimeTool implements ITool {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    @Override
    public String name() {
        return "getCurrentTime";
    }

    @Override
    public String description() {
        return "Get the current date and time in the server's local timezone. "
                + "Use this when the user asks what time it is now.";
    }

    @Override
    public String execute(String argumentsJson) {
        return FORMATTER.format(ZonedDateTime.now());
    }
}
