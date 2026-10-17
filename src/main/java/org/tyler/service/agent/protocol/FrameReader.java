package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental LF framing with a bound on incomplete bytes, including split UTF-8 sequences.
 * Returned messages are shape-validated only; session/direction checks must precede semantics.
 */
public final class FrameReader {
    private final MessageCodec codec;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    public FrameReader(MessageCodec codec) {
        this.codec = codec;
    }

    public List<JsonNode> feed(byte[] chunk) {
        List<JsonNode> messages = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < chunk.length; i++) {
            if (chunk[i] == '\n') {
                append(chunk, start, i - start);
                messages.add(codec.decode(pending.toByteArray()));
                pending.reset();
                start = i + 1;
            }
        }
        append(chunk, start, chunk.length - start);
        return messages;
    }

    private void append(byte[] chunk, int start, int length) {
        if ((long) pending.size() + length > MessageCodec.MAX_FRAME_BYTES) {
            throw new ProtocolException("MESSAGE_TOO_LARGE");
        }
        pending.write(chunk, start, length);
    }

    public void finish() {
        if (pending.size() > 0) {
            throw new ProtocolException("INVALID_FRAME");
        }
    }
}
