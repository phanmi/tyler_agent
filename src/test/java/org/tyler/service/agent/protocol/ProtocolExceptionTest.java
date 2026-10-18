package org.tyler.service.agent.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Protocol failures expose stable codes without carrying a raw parser cause. */
class ProtocolExceptionTest {
    @Test
    void retainsOnlyTheSafeCodeAsItsMessage() {
        ProtocolException failure = new ProtocolException("INVALID_FRAME");
        assertEquals("INVALID_FRAME", failure.code());
        assertEquals("INVALID_FRAME", failure.getMessage());
        assertNull(failure.getCause());
    }
}
