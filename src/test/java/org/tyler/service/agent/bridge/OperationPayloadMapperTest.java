package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.tyler.model.food.Food;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class OperationPayloadMapperTest {
    private final OperationPayloadMapper mapper = new OperationPayloadMapper();

    @Test
    void convertsExactDecimalsWithoutChangingNulls() {
        var food = mapper.read(ServiceOperationDispatcherTest.input("food").get("food"), Food.class);
        assertEquals(new BigDecimal("16.900"), food.macroNutrients().protein());
        assertEquals("16.900", mapper.value("food", food).path("food").path("macroNutrients").path("protein").textValue());
        assertEquals("2000", mapper.value("number", new BigDecimal("2E+3")).get("number").textValue());
        assertTrue(mapper.value("value", null).get("value").isNull());
    }

    @Test
    void decodingFailureRetainsCauseAndUsesSafeInternalMessage() {
        var failure = assertThrowsExactly(IllegalStateException.class,
                () -> mapper.read(JsonNodeFactory.instance.textNode("synthetic-private-value"), Food.class));
        assertInstanceOf(JsonProcessingException.class, failure.getCause());
        assertEquals("Cannot decode agent operation input", failure.getMessage());
    }

    @Test
    void serializationFailureRetainsCauseAndIsNotDomainValidation() {
        var original = new IllegalStateException("synthetic-private-value");
        Object broken = new Object() {
            public String getValue() { throw original; }
        };
        var failure = assertThrowsExactly(IllegalStateException.class, () -> mapper.value("result", broken));
        assertEquals("Cannot encode agent operation result", failure.getMessage());
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertSame(original, root);
    }
}
