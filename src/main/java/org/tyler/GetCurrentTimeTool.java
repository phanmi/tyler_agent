package org.tyler;

import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 返回服务器本地时区的当前日期与时间。
 */
@Component
public class GetCurrentTimeTool implements AgentTool {

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
