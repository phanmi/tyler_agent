package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict version 1 transport codec; domain validation remains in the application services. */
public final class MessageCodec {
    public static final int MAX_FRAME_BYTES = MessageValidator.MAX_FRAME_BYTES;
    private final ObjectMapper mapper;
    private final MessageValidator validator = new MessageValidator();

    public MessageCodec() {
        JsonFactory factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(MAX_FRAME_BYTES).build()).build();
        mapper = new ObjectMapper(factory).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    MessageValidator validator() {
        return validator;
    }

    /** Delegate shape validation without changing the codec's existing public entry point. */
    public void validateShape(JsonNode message) {
        validator.validateShape(message);
    }

    /** Requires a shape-valid message and delegates to the shared wire validator. */
    public void validateSemantics(JsonNode message) {
        validator.validateSemantics(message);
    }

    /**
     * Decode the bytes preceding LF and validate shape only. The receiver must check session and
     * direction before calling validateSemantics, then check state/correlation before dispatch.
     */
    public JsonNode decode(byte[] frame) {
        MessageValidator.validateFrame(frame);
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
        validator.validateUnicode(message);
        validateShape(message);
        return message;
    }

    /** Reject invalid locally generated values before writing a frame to the peer. */
    public byte[] encode(JsonNode message) {
        validator.validateUnicode(message);
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
