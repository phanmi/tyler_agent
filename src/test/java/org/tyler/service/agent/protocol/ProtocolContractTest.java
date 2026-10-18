package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Shared sequence fixtures keep the Java and Python session models in agreement. */
class ProtocolContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageCodec CODEC = new MessageCodec();

    static Stream<JsonNode> messages() throws IOException { return ProtocolTestFixtures.cases("messages"); }
    static Stream<JsonNode> sequences() throws IOException { return ProtocolTestFixtures.cases("sequences"); }

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
                // Inject raw peer bytes: the outbound encoder must not pre-reject invalid test input.
                byte[] frame = MAPPER.writeValueAsBytes(message);
                byte[] encoded = Arrays.copyOf(frame, frame.length + 1);
                encoded[frame.length] = '\n';
                FrameReader reader = new FrameReader(CODEC);
                List<JsonNode> decoded = reader.feed(encoded);
                reader.finish();
                assertEquals(1, decoded.size());
                actual = session.accept(decoded.getFirst(), step.path("from").asText());
            }
            for (var field : step.path("expected").properties()) {
                assertEquals(field.getValue(), actual.path(field.getKey()), fixture.path("id").asText() + ": " + field.getKey());
            }
        }
    }

}
