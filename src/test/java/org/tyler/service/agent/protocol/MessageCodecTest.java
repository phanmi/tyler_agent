package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Shared message fixtures verify the codec's public validation and serialization contract. */
class MessageCodecTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageCodec CODEC = new MessageCodec();

    static Stream<JsonNode> messages() throws IOException { return ProtocolTestFixtures.cases("messages"); }

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
                byte[] frame = assertDoesNotThrow(() -> MAPPER.writeValueAsBytes(message));
                assertEquals(message, CODEC.decode(frame), "Decoding must defer semantic checks to the receiver");
                assertEquals(expected.path("semantic").path("reason").asText(), assertThrowsExactly(ProtocolException.class,
                        () -> CODEC.validateSemantics(message)).code());
                assertEquals(expected.path("semantic").path("reason").asText(), assertThrowsExactly(ProtocolException.class,
                        () -> CODEC.encode(message)).code(), "Outbound encoding must still validate semantic values");
            } else {
                byte[] encoded = CODEC.encode(message);
                assertEquals('\n', encoded[encoded.length - 1]);
                assertEquals(message, CODEC.decode(Arrays.copyOf(encoded, encoded.length - 1)));
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
