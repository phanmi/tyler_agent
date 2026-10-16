package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Strict version 1 transport codec; domain validation remains in the application services. */
public final class MessageCodec {
    public static final int MAX_FRAME_BYTES = 16_777_216;
    private static final Set<String> DECIMALS = Set.of("amount", "calories", "protein", "carbs",
            "fat", "fiber", "weight", "Age");
    private static final Set<String> OPERATIONS = Set.of("food.insert", "workout_plan.generate_save",
            "user_info.read", "file.read", "file.write", "time.current");
    private final ObjectMapper mapper;
    private final JsonSchema schema;

    public MessageCodec() {
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(MAX_FRAME_BYTES).build()).build();
        mapper = new ObjectMapper(factory).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try (var input = MessageCodec.class.getResourceAsStream("/agent-protocol/message.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing agent protocol schema");
            }
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(mapper.readTree(input));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load agent protocol schema", e);
        }
    }

    public void validateShape(JsonNode message) {
        if (message.isObject() && message.has("protocolVersion")
                && (!message.get("protocolVersion").isNumber()
                || message.get("protocolVersion").decimalValue().compareTo(BigDecimal.ONE) != 0)) {
            throw new ProtocolException("UNSUPPORTED_VERSION");
        }
        String kind = message.path("type").asText();
        JsonNode operation = message.path("payload").path("operation");
        if ((kind.equals("operation_request") || kind.equals("operation_result"))
                && operation.isTextual() && !OPERATIONS.contains(operation.asText())) {
            throw new ProtocolException("UNKNOWN_OPERATION");
        }
        if (!schema.validate(message).isEmpty()) {
            throw new ProtocolException("INVALID_MESSAGE");
        }
    }

    public void validateSemantics(JsonNode message) {
        JsonNode payload = message.path("payload");
        if (message.path("type").asText().equals("run")
                && (payload.path("model").asText().isBlank() || payload.path("apiKey").asText().isBlank()
                || payload.path("operationTimeoutMs").longValue() > payload.path("timeoutMs").longValue())) {
            throw new ProtocolException("INVALID_VALUE");
        }
        validateNumbers(payload);
    }

    private void validateNumbers(JsonNode value) {
        if (value.isObject()) {
            value.properties().forEach(entry -> {
                JsonNode item = entry.getValue();
                try {
                    if (item.isTextual() && DECIMALS.contains(entry.getKey())) {
                        new BigDecimal(item.textValue());
                    }
                    if (entry.getKey().equals("id") && item.isTextual()
                            && new BigInteger(item.textValue()).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                        throw new ProtocolException("INVALID_VALUE");
                    }
                } catch (NumberFormatException e) {
                    throw new ProtocolException("INVALID_VALUE");
                }
                validateNumbers(item);
            });
        } else if (value.isArray()) {
            value.forEach(this::validateNumbers);
        }
    }

    /**
     * Decode the bytes preceding LF and validate shape only. The receiver must check session and
     * direction before calling validateSemantics, then check state/correlation before dispatch.
     */
    public JsonNode decode(byte[] frame) {
        if (frame.length > MAX_FRAME_BYTES) {
            throw new ProtocolException("MESSAGE_TOO_LARGE");
        }
        if (frame.length == 0 || frame[frame.length - 1] == '\r'
                || (frame.length >= 3 && frame[0] == (byte) 0xef && frame[1] == (byte) 0xbb && frame[2] == (byte) 0xbf)) {
            throw new ProtocolException("INVALID_FRAME");
        }
        JsonNode message;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(frame)).toString();
            message = mapper.readTree(text);
        } catch (JsonProcessingException | CharacterCodingException e) {
            throw new ProtocolException("INVALID_FRAME");
        }
        if (message == null || message.isMissingNode()) {
            throw new ProtocolException("INVALID_FRAME");
        }
        validateUnicode(message);
        validateShape(message);
        return message;
    }

    private void validateUnicode(JsonNode value) {
        if (value.isTextual()) {
            validateString(value.textValue());
        } else if (value.isObject()) {
            value.properties().forEach(entry -> {
                validateString(entry.getKey());
                validateUnicode(entry.getValue());
            });
        } else if (value.isArray()) {
            value.forEach(this::validateUnicode);
        }
    }

    private void validateString(String text) {
        for (int i = 0; i < text.length(); i++) {
            char unit = text.charAt(i);
            if (Character.isHighSurrogate(unit)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new ProtocolException("INVALID_FRAME");
                }
            } else if (Character.isLowSurrogate(unit)) {
                throw new ProtocolException("INVALID_FRAME");
            }
        }
    }

    /** Reject invalid locally generated values before writing a frame to the peer. */
    public byte[] encode(JsonNode message) {
        validateUnicode(message);
        try {
            byte[] frame = mapper.writeValueAsBytes(message);
            validateSemantics(decode(frame));
            byte[] terminated = java.util.Arrays.copyOf(frame, frame.length + 1);
            terminated[frame.length] = '\n';
            return terminated;
        } catch (JsonProcessingException e) {
            throw new ProtocolException("INVALID_FRAME");
        }
    }
}
