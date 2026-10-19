package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Direct validator coverage protects the shared rules and rejection precedence. */
class MessageValidatorTest {
    private static final MessageValidator VALIDATOR = new MessageValidator();

    @Test
    void supportedOperationsComeFromTheSchemaAndCannotBeMutated() throws Exception {
        try (var input = MessageValidator.class.getResourceAsStream("/agent-protocol/message.schema.json")) {
            assertNotNull(input);
            var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            var expected = new java.util.HashSet<String>();
            schema.path("$defs").path("operation").path("enum").forEach(name -> expected.add(name.textValue()));
            assertEquals(expected, VALIDATOR.supportedOperations());
            assertThrowsExactly(UnsupportedOperationException.class,
                    () -> VALIDATOR.supportedOperations().add("unapproved.operation"));
        }
    }

    static Stream<JsonNode> messages() throws IOException {
        return ProtocolTestFixtures.cases("messages");
    }

    @ParameterizedTest
    @MethodSource("messages")
    void validatesSharedMessageContract(JsonNode fixture) {
        JsonNode expected = fixture.path("expected");
        JsonNode semantic = expected.path("semantic");
        if (!expected.path("schemaValid").asBoolean() || (expected.has("semantic") && !semantic.path("valid").asBoolean())) {
            String code = expected.path("schemaValid").asBoolean()
                    ? semantic.path("reason").asText() : expected.path("reason").asText();
            assertEquals(code, assertThrowsExactly(ProtocolException.class,
                    () -> VALIDATOR.validateMessage(fixture.path("message"))).code(), fixture.path("id").asText());
        } else {
            assertDoesNotThrow(() -> VALIDATOR.validateMessage(fixture.path("message")), fixture.path("id").asText());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "true, true, python, INVALID_MESSAGE",
            "false, true, python, SESSION_MISMATCH",
            "false, false, python, WRONG_DIRECTION",
            "false, false, java, INVALID_VALUE"
    })
    void preservesReceivedValidationPrecedence(boolean missingField, boolean wrongSession, String sender, String code) {
        ObjectNode message = ProtocolTestFixtures.message("run");
        String sessionId = message.path("sessionId").asText();
        ObjectNode payload = (ObjectNode) message.path("payload");
        payload.put("model", " ");
        if (missingField) payload.remove("apiKey");
        if (wrongSession) message.put("sessionId", "f".repeat(32));
        assertEquals(code, assertThrowsExactly(ProtocolException.class,
                () -> VALIDATOR.validateReceived(message, sessionId, sender)).code());
    }

    @ParameterizedTest
    @CsvSource({"ready, python", "run, java", "food_request, python", "food_result, java",
            "completed, python", "failed_provider, python", "cancel, java", "shutdown, java"})
    void acceptsOnlyTheDefinedDirection(String fixture, String sender) {
        ObjectNode message = ProtocolTestFixtures.message(fixture);
        String sessionId = message.path("sessionId").asText();
        assertDoesNotThrow(() -> VALIDATOR.validateReceived(message, sessionId, sender));
        String wrongSender = sender.equals("java") ? "python" : "java";
        assertEquals("WRONG_DIRECTION", assertThrowsExactly(ProtocolException.class,
                () -> VALIDATOR.validateReceived(message, sessionId, wrongSender)).code());
    }

    @Test
    void frameSizeLimitIncludesTheBoundaryAndUsesLongArithmetic() {
        assertDoesNotThrow(() -> MessageValidator.validateFrameSize(MessageValidator.MAX_FRAME_BYTES));
        assertEquals("MESSAGE_TOO_LARGE", assertThrowsExactly(ProtocolException.class,
                () -> MessageValidator.validateFrameSize((long) Integer.MAX_VALUE + 1)).code());
    }

    @Test
    void rejectsUnpairedSurrogatesInKeysAndNestedValues() {
        ObjectNode message = ProtocolTestFixtures.message("run");
        ((ObjectNode) message.path("payload")).put("\uD800", "synthetic-private-value");
        assertEquals("INVALID_FRAME", assertThrowsExactly(ProtocolException.class,
                () -> VALIDATOR.validateUnicode(message)).code());
        ObjectNode nestedMessage = ProtocolTestFixtures.message("run");
        ((ObjectNode) nestedMessage.path("payload")).putArray("extra").add("\uDC00");
        assertEquals("INVALID_FRAME", assertThrowsExactly(ProtocolException.class,
                () -> VALIDATOR.validateUnicode(nestedMessage)).code());
    }

    @Test
    void standaloneBridgeValidationReusesWireRulesWithoutApplyingDomainRules() {
        var input = (ObjectNode) ProtocolTestFixtures.message("food_request").path("payload").path("input");
        var info = (ObjectNode) input.path("food").path("genericInfo");
        info.put("amount", "-0.01").put("date", "not-a-date");
        assertDoesNotThrow(() -> VALIDATOR.validateOperationInput("food.insert", input));
        info.put("amount", "2000000001");
        assertEquals("INVALID_VALUE", assertThrowsExactly(ProtocolException.class,
                () -> VALIDATOR.validateOperationInput("food.insert", input)).code());
        info.put("amount", "1").put("foodName", "\uD800");
        assertEquals("INVALID_FRAME", assertThrowsExactly(ProtocolException.class,
                () -> VALIDATOR.validateOperationInput("food.insert", input)).code());
        assertEquals("INVALID_MESSAGE", assertThrowsExactly(ProtocolException.class,
                () -> VALIDATOR.validateOperationInput("food.insert", null)).code());
    }
}
