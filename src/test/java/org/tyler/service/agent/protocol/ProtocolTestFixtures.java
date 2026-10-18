package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Load the shared contract data without copying wire messages into individual tests. */
final class ProtocolTestFixtures {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProtocolTestFixtures() {
    }

    static Stream<JsonNode> cases(String name) throws IOException {
        try (var input = ProtocolTestFixtures.class.getResourceAsStream("/agent-protocol/fixtures/" + name + ".json")) {
            if (input == null) {
                throw new IllegalStateException("Missing protocol test fixture: " + name);
            }
            return StreamSupport.stream(MAPPER.readTree(input).path("cases").spliterator(), false);
        }
    }

    static ObjectNode message(String id) {
        try (var fixtures = cases("messages")) {
            return fixtures.filter(value -> id.equals(value.path("id").asText()))
                    .map(value -> (ObjectNode) value.path("message").deepCopy()).findFirst().orElseThrow();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
