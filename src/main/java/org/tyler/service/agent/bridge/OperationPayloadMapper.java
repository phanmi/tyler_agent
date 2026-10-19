package org.tyler.service.agent.bridge;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;

/** Shared transport conversion; configured once and never exposed for mutation by handlers. */
public final class OperationPayloadMapper {
    private final ObjectMapper mapper;

    public OperationPayloadMapper() {
        SimpleModule decimals = new SimpleModule();
        decimals.addSerializer(BigDecimal.class, new JsonSerializer<>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator output, SerializerProvider provider) throws IOException {
                output.writeString(value.toPlainString());
            }
        });
        mapper = new ObjectMapper().registerModule(decimals);
    }

    /** Input shape has already been validated; conversion failure is an internal failure. */
    public <T> T read(JsonNode input, Class<T> type) {
        try {
            return mapper.treeToValue(input, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot decode agent operation input", e);
        }
    }

    /** Serialization may follow a committed write; never classify failure as invalid user input. */
    public ObjectNode value(String field, Object value) {
        try {
            return mapper.createObjectNode().set(field, mapper.valueToTree(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cannot encode agent operation result", e);
        }
    }
}
