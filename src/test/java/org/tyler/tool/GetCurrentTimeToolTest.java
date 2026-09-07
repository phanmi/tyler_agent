package org.tyler.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GetCurrentTimeToolTest {

    @Test
    void exposesExpectedName() {
        assertEquals("getCurrentTime", new GetCurrentTimeTool().name());
    }

    @Test
    void returnsNonBlankTime() {
        GetCurrentTimeTool tool = new GetCurrentTimeTool();
        String result = tool.execute("{}");
        assertNotNull(result);
        assertFalse(result.isBlank());
    }
}
