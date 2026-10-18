package org.tyler.service.agent.python;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PythonAgentSettingsTest {
    @Test
    void defaultsUseTheProtocolBoundsAndCopyTheCommand() {
        var command = new ArrayList<>(List.of("worker with spaces.exe"));
        var settings = PythonAgentSettings.defaults(command);
        command.clear();
        assertEquals(List.of("worker with spaces.exe"), settings.command());
        assertEquals(16, settings.queueCapacity());
        assertEquals(Duration.ofSeconds(30), settings.startupTimeout());
        assertEquals(Duration.ofSeconds(30), settings.queueTimeout());
        assertEquals(Duration.ofSeconds(5), settings.shutdownGrace());
    }

    @Test
    void rejectsUnsupportedTimeoutsAndOperationDeadlineBeyondRun() {
        var settings = PythonAgentSettings.defaults(List.of("worker"));
        for (Duration duration : List.of(Duration.ofMillis(999), Duration.ofHours(2))) {
            assertThrows(IllegalArgumentException.class, () -> new PythonAgentSettings(settings.command(),
                    settings.startupTimeout(), settings.queueTimeout(), duration, settings.operationTimeout(),
                    settings.shutdownGrace(), settings.queueCapacity()));
        }
        assertThrows(IllegalArgumentException.class, () -> new PythonAgentSettings(settings.command(),
                settings.startupTimeout(), settings.queueTimeout(), Duration.ofSeconds(1), Duration.ofSeconds(2),
                settings.shutdownGrace(), settings.queueCapacity()));
        assertThrows(IllegalArgumentException.class, () -> PythonAgentSettings.defaults(List.of(" ")));
    }
}
