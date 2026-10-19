package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tyler.service.agent.protocol.FrameReader;
import org.tyler.service.agent.protocol.MessageCodec;
import org.tyler.service.agent.protocol.ProtocolException;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/** One reader/writer per pipe; writes and stderr draining cannot block protocol decisions. */
final class WorkerPipe implements AutoCloseable {
    private static final String WORKER_UNAVAILABLE = "WORKER_UNAVAILABLE";
    private static final Logger log = LoggerFactory.getLogger(WorkerPipe.class);
    private final Process process;
    private final MessageCodec codec = new MessageCodec();
    private final ArrayBlockingQueue<byte[]> outgoing = new ArrayBlockingQueue<>(4);
    private final Consumer<String> failure;
    private Thread writer;

    WorkerPipe(Process process, Consumer<String> failure) {
        this.process = process;
        this.failure = failure;
    }

    void start(Consumer<JsonNode> receiver) {
        writer = Thread.ofVirtual().name("agent-pipe-writer").start(this::write);
        Thread.ofVirtual().name("agent-pipe-reader").start(() -> read(receiver));
        Thread.ofVirtual().name("agent-stderr-drain").start(this::drainErrors);
    }

    void send(JsonNode message) {
        if (!outgoing.offer(codec.encode(message))) throw new ProtocolException(WORKER_UNAVAILABLE);
    }

    private void read(Consumer<JsonNode> receiver) {
        FrameReader reader = new FrameReader(codec);
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = process.getInputStream().read(buffer)) >= 0) {
                for (JsonNode message : reader.feed(Arrays.copyOf(buffer, count))) receiver.accept(message);
            }
            reader.finish();
            failure.accept(WORKER_UNAVAILABLE);
        } catch (ProtocolException e) {
            failure.accept(e.code());
        } catch (IOException e) {
            failure.accept(WORKER_UNAVAILABLE);
        }
    }

    private void write() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] frame = outgoing.take();
                try {
                    process.getOutputStream().write(frame);
                    process.getOutputStream().flush();
                } finally {
                    Arrays.fill(frame, (byte) 0);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            failure.accept(WORKER_UNAVAILABLE);
        }
    }

    private void drainErrors() {
        // Even an unterminated hostile line has constant memory use. Never forward remote text.
        long bytes = 0;
        try {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = process.getErrorStream().read(buffer)) >= 0) bytes += count;
        } catch (IOException ignored) {
            // Closing the process concurrently is normal cleanup.
        }
        if (bytes > 0) log.info("Agent worker stderr discarded: bytes={}", bytes);
    }

    @Override
    public void close() {
        if (writer != null) writer.interrupt();
        outgoing.clear();
        // Terminating the process first releases blocked native pipe reads/writes on Windows.
        process.destroyForcibly();
    }
}
