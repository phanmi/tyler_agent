package org.tyler.service.agent.bridge;

import org.junit.jupiter.api.Test;
import org.tyler.service.agent.bridge.handler.CurrentTimeHandler;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.tyler.service.agent.bridge.ServiceOperationDispatcherTest.input;

class CurrentTimeHandlerTest {
    @Test
    void usesInjectedClockAndItsTimeZone() {
        var instant = java.time.Instant.parse("2026-10-16T16:34:56Z");
        var eastern = new CurrentTimeHandler(java.time.Clock.fixed(instant, java.time.ZoneId.of("America/New_York")));
        var utc = new CurrentTimeHandler(java.time.Clock.fixed(instant, java.time.ZoneId.of("UTC")));
        assertEquals("2026-10-16 12:34:56 EDT", eastern.execute(input("time")).get("text").textValue());
        assertEquals("2026-10-16 16:34:56 UTC", utc.execute(input("time")).get("text").textValue());
    }
}
