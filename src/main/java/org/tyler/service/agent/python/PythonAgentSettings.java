package org.tyler.service.agent.python;

import java.time.Duration;
import java.util.List;

/** Immutable process arguments and protocol bounds. Arguments are passed without a shell. */
public record PythonAgentSettings(List<String> command, Duration startupTimeout, Duration queueTimeout,
                                  Duration runTimeout, Duration operationTimeout, Duration shutdownGrace,
                                  int queueCapacity) {
    public PythonAgentSettings {
        command = List.copyOf(command);
        if (command.isEmpty() || command.stream().anyMatch(String::isBlank)
                || startupTimeout.isNegative() || startupTimeout.isZero()
                || queueTimeout.isNegative() || queueTimeout.isZero()
                || shutdownGrace.isNegative() || shutdownGrace.isZero()
                || runTimeout.toMillis() < 1000 || runTimeout.toMillis() > 3_600_000
                || operationTimeout.toMillis() < 1000 || operationTimeout.compareTo(runTimeout) > 0
                || queueCapacity < 1 || queueCapacity > 16) {
            throw new IllegalArgumentException("Invalid Python agent configuration");
        }
    }

    public static PythonAgentSettings defaults(List<String> command) {
        return new PythonAgentSettings(command, Duration.ofSeconds(30), Duration.ofSeconds(30),
                Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofSeconds(5), 16);
    }
}
