package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the same committed message, byte, and sequence cases as the Python suite. */
class ProtocolContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageCodec CODEC = new MessageCodec();

    private static Stream<JsonNode> cases(String name) throws IOException {
        try (var input = ProtocolContractTest.class.getResourceAsStream("/agent-protocol/fixtures/" + name + ".json")) {
            assertNotNull(input);
            return StreamSupport.stream(MAPPER.readTree(input).path("cases").spliterator(), false);
        }
    }

    static Stream<JsonNode> messages() throws IOException { return cases("messages"); }
    static Stream<JsonNode> framing() throws IOException { return cases("framing"); }
    static Stream<JsonNode> sequences() throws IOException { return cases("sequences"); }

    @ParameterizedTest
    @MethodSource("messages")
    void validatesMessageShapeSemanticsAndRoundTrip(JsonNode fixture) {
        JsonNode message = fixture.path("message"), expected = fixture.path("expected");
        if (!expected.path("schemaValid").asBoolean()) {
            assertEquals(expected.path("reason").asText(), assertThrowsExactly(ProtocolException.class,
                    () -> CODEC.validateShape(message)).code(), fixture.path("id").asText());
        } else {
            CODEC.validateShape(message);
            if (expected.has("semantic") && !expected.path("semantic").path("valid").asBoolean()) {
                assertEquals(expected.path("semantic").path("reason").asText(), assertThrowsExactly(ProtocolException.class,
                        () -> CODEC.validateSemantics(message)).code());
            } else {
                byte[] encoded = CODEC.encode(message);
                assertEquals('\n', encoded[encoded.length - 1]);
                assertEquals(message, CODEC.decode(Arrays.copyOf(encoded, encoded.length - 1)));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("framing")
    void validatesIncrementalFraming(JsonNode fixture) throws Exception {
        List<byte[]> chunks = new ArrayList<>();
        JsonNode expected = fixture.path("expected");
        if (fixture.has("generator")) {
            JsonNode generator = fixture.path("generator");
            ObjectNode message = generator.path("message").deepCopy();
            int emptySize = MAPPER.writeValueAsBytes(message).length;
            ((ObjectNode) message.path("payload")).put("reply", "x".repeat(generator.path("frameBytes").asInt() - emptySize));
            byte[] bytes = MAPPER.writeValueAsBytes(message);
            assertEquals(generator.path("frameBytes").asInt(), bytes.length);
            chunks.add(bytes);
            chunks.add(new byte[]{'\n'});
        } else {
            fixture.path("chunksBase64").forEach(value -> chunks.add(Base64.getDecoder().decode(value.asText())));
        }
        FrameReader reader = new FrameReader(CODEC);
        List<JsonNode> decoded = new ArrayList<>();
        org.junit.jupiter.api.function.Executable read = () -> {
            for (byte[] chunk : chunks) decoded.addAll(reader.feed(chunk));
            if (fixture.path("endOfInput").asBoolean()) reader.finish();
        };
        if (!expected.path("accepted").asBoolean()) {
            assertEquals(expected.path("reason").asText(), assertThrowsExactly(ProtocolException.class, read).code(),
                    fixture.path("id").asText());
        } else {
            assertDoesNotThrow(read);
            if (expected.has("messages")) assertEquals(expected.path("messages"), MAPPER.valueToTree(decoded));
            if (expected.has("messageCount")) {
                assertEquals(expected.path("messageCount").asInt(), decoded.size());
                assertEquals(expected.path("replyBytes").asInt(), decoded.getFirst().path("payload").path("reply")
                        .asText().getBytes(StandardCharsets.UTF_8).length);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("sequences")
    void validatesSequenceAndEffectLedger(JsonNode fixture) throws Exception {
        Map<String, JsonNode> messages = messages().collect(Collectors.toMap(value -> value.path("id").asText(), value -> value.path("message")));
        ProtocolSession session = new ProtocolSession(messages.get("ready").path("sessionId").asText(), CODEC);
        assertEquals(fixture.path("initialState").asText(), session.state());
        for (JsonNode step : fixture.path("steps")) {
            ObjectNode actual;
            if (step.has("event")) {
                actual = session.observe(step.path("event").asText(), step.path("outcome").asText(), step.path("operationId").asText());
            } else {
                ObjectNode message = messages.get(step.path("ref").asText()).deepCopy();
                for (var replacement : step.path("set").properties()) {
                    String pointer = replacement.getKey();
                    int slash = pointer.lastIndexOf('/');
                    String key = pointer.substring(slash + 1).replace("~1", "/").replace("~0", "~");
                    ObjectNode target = (ObjectNode) message.at(pointer.substring(0, slash));
                    assertTrue(target.has(key));
                    target.set(key, replacement.getValue());
                }
                byte[] encoded = CODEC.encode(message);
                actual = session.accept(CODEC.decode(Arrays.copyOf(encoded, encoded.length - 1)), step.path("from").asText());
            }
            for (var field : step.path("expected").properties()) {
                assertEquals(field.getValue(), actual.path(field.getKey()), fixture.path("id").asText() + ": " + field.getKey());
            }
        }
    }

    @Test
    void parserDiagnosticsNeverRetainSensitiveFrames() {
        ProtocolException failure = assertThrowsExactly(ProtocolException.class,
                () -> CODEC.decode("{\"apiKey\":\"synthetic-secret\"".getBytes(StandardCharsets.UTF_8)));
        assertEquals("INVALID_FRAME", failure.getMessage());
        assertNull(failure.getCause());
    }
}
