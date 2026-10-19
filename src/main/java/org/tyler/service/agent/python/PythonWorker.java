package org.tyler.service.agent.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tyler.service.agent.protocol.MessageCodec;
import org.tyler.service.agent.protocol.ProtocolException;
import org.tyler.service.agent.protocol.ProtocolFailures;
import org.tyler.service.agent.protocol.ProtocolSession;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Owns one private worker process. All ledger changes use lock; blocking I/O, service callbacks,
 * and caller waits run separately. A timed-out service is never interrupted or replayed.
 */
public final class PythonWorker implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PythonWorker.class);
    private final Object lock = new Object();
    private final PythonAgentSettings settings;
    private final IOperationDispatcher dispatcher;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-deadlines").factory());
    private final java.util.concurrent.ExecutorService operations = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("agent-operation-", 0).factory());
    private Connection connection;
    private boolean closed;
    private int pendingOperations;

    public PythonWorker(PythonAgentSettings settings, IOperationDispatcher dispatcher) {
        this.settings = settings;
        this.dispatcher = dispatcher;
    }

    /** Compatibility is checked before Spring announces backend readiness; no key is read here. */
    public void start() {
        Connection current;
        synchronized (lock) {
            if (closed || pendingOperations > 0) throw new ProtocolException("WORKER_UNAVAILABLE");
            if (connection != null && !connection.broken && connection.runs >= 10_000
                    && connection.session.state().equals("IDLE")) breakConnection(connection, "WORKER_UNAVAILABLE");
            if (connection == null || connection.broken) connection = launch();
            current = connection;
        }
        try {
            current.ready.get(settings.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            breakConnection(current, "REQUEST_CANCELLED");
            Thread.currentThread().interrupt();
            throw new ProtocolException("REQUEST_CANCELLED");
        } catch (TimeoutException e) {
            breakConnection(current, "DEADLINE_EXCEEDED");
            throw new ProtocolException("DEADLINE_EXCEEDED");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    private Connection launch() {
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        ProcessBuilder builder = new ProcessBuilder(settings.command());
        // Inherit only OS essentials, not credentials, PYTHONPATH, or provider configuration.
        builder.environment().keySet().removeIf(key -> !java.util.Set.of("SystemRoot", "TEMP", "TMP", "LD_LIBRARY_PATH").contains(key));
        builder.environment().put("PYTHONNOUSERSITE", "1");
        builder.environment().put("TYLER_AGENT_SESSION_ID", sessionId);
        builder.environment().put("TYLER_AGENT_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
        try {
            Connection created = new Connection(builder.start(), sessionId);
            created.pipe.start(message -> receive(created, message));
            log.info("Agent worker launched: pid={}, session={}", created.process.pid(), sessionId);
            return created;
        } catch (IOException e) {
            throw new ProtocolException("WORKER_UNAVAILABLE");
        }
    }

    /** The conversation lane guarantees at most one caller; callbacks and deadlines stay independent. */
    public String execute(ObjectNode payload) {
        start();
        Connection current;
        Run run;
        synchronized (lock) {
            current = connection;
            if (closed || current.broken || pendingOperations > 0 || !current.session.state().equals("IDLE")) {
                throw new ProtocolException("WORKER_UNAVAILABLE");
            }
            run = new Run(UUID.randomUUID().toString());
            current.run = run;
            try {
                sendAccepted(current, "run", run.id, payload);
            } catch (ProtocolException e) {
                breakConnection(current, e.code());
                throw e;
            }
            current.runs++;
            run.deadline = timers.schedule(() -> cancel(current, run, "DEADLINE_EXCEEDED"),
                    settings.runTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }
        try {
            return run.reply.get();
        } catch (InterruptedException e) {
            cancel(current, run, "REQUEST_CANCELLED");
            Thread.currentThread().interrupt();
            throw new ProtocolException("REQUEST_CANCELLED");
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /** Completion owns the outcome once accepted; history persistence remains in Java. */
    public void historySettled(boolean success) {
        synchronized (lock) {
            if (connection != null && connection.run != null && connection.run.completed) {
                connection.session.observe("history_settled", success ? "success" : "failure", "");
                connection.run = null;
            }
        }
    }

    private void receive(Connection current, JsonNode message) {
        synchronized (lock) {
            if (current.broken || closed) return;
            ObjectNode decision = current.session.accept(message, "python");
            if (decision.path("action").asText().equals("reject")) {
                breakConnection(current, decision.path("reason").asText());
                return;
            }
            if (decision.path("action").asText().equals("ignore")) return;
            switch (message.path("type").asText()) {
                case "ready" -> current.ready.complete(null);
                case "operation_request" -> dispatch(current, message.path("payload"));
                case "completed" -> complete(current, message.path("payload").path("reply").asText());
                case "failed" -> failed(current, message);
                default -> breakConnection(current, "WRONG_DIRECTION");
            }
        }
    }

    private void complete(Connection current, String reply) {
        Run run = current.run;
        run.completed = true;
        stopDeadline(run);
        run.reply.complete(reply);
    }

    private void failed(Connection current, JsonNode message) {
        Run run = current.run;
        stopDeadline(run);
        run.reply.completeExceptionally(run.withLocalCause(ProtocolFailures.translate(message, current.codec)));
        if (run.cancelled) {
            breakConnection(current, "REQUEST_CANCELLED");
            return;
        }
        if (pendingOperations > 0) {
            run.cancelled = true;
            retireAfterGrace(current);
        } else {
            current.run = null;
        }
    }

    private void dispatch(Connection current, JsonNode payload) {
        Run run = current.run;
        pendingOperations++;
        ScheduledFuture<?> timeout = timers.schedule(() -> cancel(current, run, "DEADLINE_EXCEEDED"),
                settings.operationTimeout().toMillis(), TimeUnit.MILLISECONDS);
        operations.execute(() -> {
            JsonNode result = null;
            RuntimeException failure = new IllegalStateException("Operation did not complete");
            try {
                result = dispatcher.dispatch(payload.path("operation").asText(), payload.path("input"));
                failure = null;
            } catch (RuntimeException e) {
                failure = e;
            } finally {
                timeout.cancel(false);
                settleOperation(current, run, payload, result, failure);
            }
        });
    }

    private void settleOperation(Connection current, Run run, JsonNode request, JsonNode result, RuntimeException failure) {
        synchronized (lock) {
            pendingOperations--;
            // A failed operation prevents further dispatch in this run. Keep its original Java
            // cause locally; WorkerMessages serializes only the allowlisted code and safe text.
            run.localFailure = failure;
            ObjectNode payload = WorkerMessages.result(request, result, failure);
            if (current.broken || closed || run.cancelled || run.reply.isDone()) {
                String outcome = failure == null
                        ? (java.util.Set.of("food.insert", "workout_plan.generate_save", "file.write")
                        .contains(request.path("operation").asText()) ? "committed" : "none")
                        : payload.path("error").path("writeOutcome").asText();
                current.session.observe("operation_settled", outcome, request.path("operationId").asText());
                log.info("Agent operation settled after disconnect/cancellation: operation={}, outcome={}",
                        request.path("operation").asText(), outcome);
                return;
            }
            try {
                sendAccepted(current, "operation_result", run.id, payload);
            } catch (ProtocolException e) {
                breakConnection(current, e.code());
            }
        }
    }

    private void cancel(Connection current, Run run, String code) {
        synchronized (lock) {
            if (closed || current.broken || current.run != run || run.completed || run.cancelled) return;
            run.cancelled = true;
            stopDeadline(run);
            String reason = code.equals("REQUEST_CANCELLED") ? "user_cancelled" : "deadline_exceeded";
            try {
                sendAccepted(current, "cancel", run.id, WorkerMessages.reason(reason));
            } catch (ProtocolException e) {
                breakConnection(current, e.code());
            }
            run.reply.completeExceptionally(run.withLocalCause(new ProtocolException(code)));
            log.info("Agent run cancelled: request={}, code={}, pendingOperations={}", run.id, code, pendingOperations);
            retireAfterGrace(current);
        }
    }

    private void retireAfterGrace(Connection current) {
        timers.schedule(() -> breakConnection(current, "WORKER_UNAVAILABLE"),
                settings.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void sendAccepted(Connection current, String type, String request, JsonNode payload) {
        ObjectNode message = WorkerMessages.envelope(current.id, type, request, payload);
        ObjectNode decision = current.session.accept(message, "java");
        if (!decision.path("action").asText().equals("accept")) {
            throw new ProtocolException(decision.path("reason").asText());
        }
        current.pipe.send(message);
    }

    private void breakConnection(Connection current, String code) {
        synchronized (lock) {
            if (current.broken) return;
            current.broken = true;
            current.session.observe("pipe_eof", "", "");
            current.ready.completeExceptionally(new ProtocolException(code));
            if (current.run != null) {
                stopDeadline(current.run);
                current.run.reply.completeExceptionally(current.run.withLocalCause(new ProtocolException(code)));
            }
            current.pipe.close();
            log.info("Agent worker closed: pid={}, reason={}, pendingOperations={}",
                    current.process.pid(), closed ? "shutdown" : code, pendingOperations);
        }
    }

    private void stopDeadline(Run run) {
        if (run.deadline != null) run.deadline.cancel(false);
    }

    private RuntimeException unwrap(ExecutionException failure) {
        return failure.getCause() instanceof RuntimeException runtime ? runtime : new ProtocolException("INTERNAL_FAILURE");
    }

    public long pid() {
        synchronized (lock) { return connection == null ? -1 : connection.process.pid(); }
    }

    @Override
    public void close() {
        Connection current;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            current = connection;
            if (current != null && !current.broken) {
                try { sendAccepted(current, "shutdown", null, WorkerMessages.reason("application_exit")); }
                catch (ProtocolException ignored) { /* Already closing; termination below remains authoritative. */ }
                current.ready.completeExceptionally(new ProtocolException("REQUEST_CANCELLED"));
                if (current.run != null) current.run.reply.completeExceptionally(
                        current.run.withLocalCause(new ProtocolException("REQUEST_CANCELLED")));
            }
        }
        if (current != null) {
            try {
                if (!current.process.waitFor(settings.shutdownGrace().toMillis(), TimeUnit.MILLISECONDS)) current.process.destroyForcibly();
            } catch (InterruptedException e) {
                current.process.destroyForcibly();
                Thread.currentThread().interrupt();
            } finally { breakConnection(current, "WORKER_UNAVAILABLE"); }
        }
        timers.shutdownNow();
        operations.shutdown(); // Do not interrupt a transaction to meet a process shutdown timer.
    }

    private final class Connection {
        final Process process;
        final String id;
        final MessageCodec codec = new MessageCodec();
        final ProtocolSession session;
        final WorkerPipe pipe;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        Run run;
        boolean broken;
        int runs;

        Connection(Process process, String id) {
            this.process = process;
            this.id = id;
            session = new ProtocolSession(id, codec);
            pipe = new WorkerPipe(process, code -> breakConnection(this, code));
        }
    }

    private static final class Run {
        final String id;
        final CompletableFuture<String> reply = new CompletableFuture<>();
        ScheduledFuture<?> deadline;
        RuntimeException localFailure;
        boolean cancelled;
        boolean completed;

        Run(String id) { this.id = id; }

        /** Public text is Java-owned; only locally raised exceptions may enter the cause chain. */
        RuntimeException withLocalCause(RuntimeException safeFailure) {
            if (localFailure != null) safeFailure.initCause(localFailure);
            return safeFailure;
        }
    }
}
