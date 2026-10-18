package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Shared byte fixtures exercise incremental reads, split UTF-8, and bounded buffering. */
class FrameReaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageCodec CODEC = new MessageCodec();

    static Stream<JsonNode> framing() throws IOException { return ProtocolTestFixtures.cases("framing"); }

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

}
