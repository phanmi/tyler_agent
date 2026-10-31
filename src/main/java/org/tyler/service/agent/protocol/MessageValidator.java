package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared protocol version 2 wire validation. Domain rules remain in application services;
 * request correlation and lifecycle guards remain in the session ledger.
 */
public final class MessageValidator {
    /** The only accepted protocol version; must match contracts/agent/v2 and the Python worker. */
    public static final int PROTOCOL_VERSION = 2;
    public static final int MAX_FRAME_BYTES = 16_777_216;
    private static final BigDecimal MAX_DECIMAL_VALUE = BigDecimal.valueOf(2_000_000_000L);
    private static final Set<String> DECIMALS = Set.of("amount", "calories", "protein", "carbs",
            "fat", "fiber", "weight", "Age");
    private static final Map<String, String> DIRECTIONS = Map.of("ready", "python", "run", "java",
            "operation_request", "python", "operation_result", "java", "completed", "python",
            "failed", "python", "cancel", "java", "shutdown", "java");
    private final JsonSchema schema;
    private final JsonSchema operationRequestSchema;
    private final Set<String> operations;

    public MessageValidator() {
        try (var input = MessageValidator.class.getResourceAsStream("/agent-protocol/message.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing agent protocol schema");
            }
            JsonNode document = new ObjectMapper().readTree(input);
            operations = readOperations(document);
            var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            schema = factory.getSchema(document);
            var operationDocument = JsonNodeFactory.instance.objectNode()
                    .put("$ref", "#/$defs/operationRequest");
            operationDocument.set("$defs", document.path("$defs"));
            operationRequestSchema = factory.getSchema(operationDocument);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load agent protocol schema", e);
        }
    }

    /** Immutable operation allowlist from the shared schema, also used for handler registration. */
    public Set<String> supportedOperations() {
        return operations;
    }

    private static Set<String> readOperations(JsonNode document) {
        JsonNode names = document.path("$defs").path("operation").path("enum");
        if (!names.isArray() || names.isEmpty()) {
            throw new IllegalStateException("Agent protocol schema has no operation allowlist");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode name : names) {
            if (!name.isTextual() || name.textValue().isBlank() || !result.add(name.textValue())) {
                throw new IllegalStateException("Agent protocol schema has an invalid operation allowlist");
            }
        }
        return Set.copyOf(result);
    }

    /** Shape checks always precede semantic checks for locally generated or translated messages. */
    public void validateMessage(JsonNode message) {
        validateShape(message);
        validateSemantics(message);
    }

    /**
     * Protect direct bridge callers using the same operation schema and decimal rules as the wire.
     * Session, direction, and correlation are still checked by the supervisor before dispatch.
     */
    public void validateOperationInput(String operation, JsonNode input) {
        if (operation != null && !operations.contains(operation)) {
            throw new ProtocolException("UNKNOWN_OPERATION");
        }
        var payload = JsonNodeFactory.instance.objectNode().put("operationId", "bridge")
                .put("operation", operation);
        payload.set("input", input);
        if (!operationRequestSchema.validate(payload).isEmpty()) {
            throw new ProtocolException("INVALID_MESSAGE");
        }
        validateUnicode(input);
        validateNumbers(input);
    }

    /** Validate shape, session, direction, then values before any state change or dispatch. */
    public void validateReceived(JsonNode message, String sessionId, String sender) {
        validateShape(message);
        if (!sessionId.equals(message.path("sessionId").asText())) {
            throw new ProtocolException("SESSION_MISMATCH");
        }
        if (!sender.equals(DIRECTIONS.get(message.path("type").asText()))) {
            throw new ProtocolException("WRONG_DIRECTION");
        }
        validateSemantics(message);
    }

    /** Version and operation errors have precedence over other schema failures. */
    public void validateShape(JsonNode message) {
        validateVersion(message);
        validateOperation(message);
        if (!schema.validate(message).isEmpty()) {
            throw new ProtocolException("INVALID_MESSAGE");
        }
    }

    private void validateVersion(JsonNode message) {
        if (!message.isObject() || !message.has("protocolVersion")) {
            return;
        }
        JsonNode version = message.get("protocolVersion");
        if (!version.isNumber() || version.decimalValue().compareTo(BigDecimal.valueOf(PROTOCOL_VERSION)) != 0) {
            throw new ProtocolException("UNSUPPORTED_VERSION");
        }
    }

    private void validateOperation(JsonNode message) {
        String kind = message.path("type").asText();
        if (!kind.equals("operation_request") && !kind.equals("operation_result")) {
            return;
        }
        JsonNode operation = message.path("payload").path("operation");
        if (operation.isTextual() && !operations.contains(operation.asText())) {
            throw new ProtocolException("UNKNOWN_OPERATION");
        }
    }

    /** Requires a shape-valid message; dates and nonnegative domain rules are not transport rules. */
    public void validateSemantics(JsonNode message) {
        JsonNode payload = message.path("payload");
        if (message.path("type").asText().equals("run")) {
            validateRunValues(payload);
        }
        validateNumbers(payload);
    }

    private void validateRunValues(JsonNode payload) {
        if (payload.path("model").asText().isBlank() || payload.path("apiKey").asText().isBlank()) {
            throw new ProtocolException("INVALID_VALUE");
        }
        if (payload.path("operationTimeoutMs").longValue() > payload.path("timeoutMs").longValue()) {
            throw new ProtocolException("INVALID_VALUE");
        }
    }

    private void validateNumbers(JsonNode value) {
        if (value.isObject()) {
            value.properties().forEach(entry -> {
                validateNumber(entry.getKey(), entry.getValue());
                validateNumbers(entry.getValue());
            });
        } else if (value.isArray()) {
            value.forEach(this::validateNumbers);
        }
    }

    private void validateNumber(String field, JsonNode value) {
        if (!value.isTextual()) {
            return;
        }
        try {
            if (DECIMALS.contains(field)
                    && new BigDecimal(value.textValue()).abs().compareTo(MAX_DECIMAL_VALUE) > 0) {
                throw new ProtocolException("INVALID_VALUE");
            }
            if (field.equals("id")
                    && new BigInteger(value.textValue()).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new ProtocolException("INVALID_VALUE");
            }
        } catch (NumberFormatException e) {
            throw new ProtocolException("INVALID_VALUE");
        }
    }

    /** Enforce the same byte bound for complete frames and partial input buffers. */
    public static void validateFrameSize(long size) {
        if (size > MAX_FRAME_BYTES) {
            throw new ProtocolException("MESSAGE_TOO_LARGE");
        }
    }

    /** Check the pre-LF bytes; strict UTF-8 and JSON parsing are the codec's responsibility. */
    public static void validateFrame(byte[] frame) {
        validateFrameSize(frame.length);
        if (frame.length == 0 || frame[frame.length - 1] == '\r' || hasByteOrderMark(frame)) {
            throw new ProtocolException("INVALID_FRAME");
        }
    }

    private static boolean hasByteOrderMark(byte[] frame) {
        return frame.length >= 3 && frame[0] == (byte) 0xef
                && frame[1] == (byte) 0xbb && frame[2] == (byte) 0xbf;
    }

    /** Reject unpaired UTF-16 surrogates in JSON text and keys without echoing their values. */
    public void validateUnicode(JsonNode value) {
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

}
