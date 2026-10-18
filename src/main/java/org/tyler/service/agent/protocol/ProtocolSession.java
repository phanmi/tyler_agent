package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure serialized protocol state and effect ledger. It authorizes no actual service calls.
 * The future supervisor must dispatch only accepted operations and report real local outcomes.
 */
public final class ProtocolSession {
    private static final Set<String> WRITES = Set.of("food.insert", "workout_plan.generate_save", "file.write");
    private final String sessionId;
    private final MessageValidator validator;
    private final Set<String> requests = new HashSet<>();
    private final Set<String> finished = new HashSet<>();
    private final Set<String> operations = new HashSet<>();
    private final Set<String> resolved = new HashSet<>();
    private String state = "STARTING";
    private String active;
    private String pendingId;
    private String pendingOperation;
    private boolean committed;
    private boolean uncertain;
    private boolean failedOperation;
    private boolean terminal;
    private boolean awaitingHistory;
    private int dispatchCount;

    public ProtocolSession(String sessionId, MessageCodec codec) {
        this.sessionId = sessionId;
        this.validator = codec.validator();
    }

    public String state() {
        return state;
    }

    private ObjectNode decision() {
        return JsonNodeFactory.instance.objectNode().put("action", "accept").put("state", state)
                .put("writeOutcome", uncertain ? "unknown" : committed ? "committed" : "none")
                .put("historyAppend", 0).put("dispatchCount", dispatchCount).put("replayCount", 0)
                .put("activeRequestId", active).put("awaitingHistory", awaitingHistory)
                .put("failedOperation", failedOperation)
                .put("laneBlocked", pendingId != null && Set.of("CANCELLING", "CLOSED", "STOPPING").contains(state));
    }

    private ObjectNode reject(String code) {
        state = "STOPPING";
        return decision().put("action", "reject").put("reason", code);
    }

    private ObjectNode ignore(String reason) {
        return decision().put("action", "ignore").put("reason", reason);
    }

    private void settleRun() {
        if (active != null) {
            finished.add(active);
        }
        if (pendingId == null && !Set.of("STOPPING", "CLOSED").contains(state)) {
            state = "IDLE";
        }
    }

    /** Validate before dispatching one message handler; rejected input never dispatches work. */
    public ObjectNode accept(JsonNode message, String sender) {
        try {
            validator.validateReceived(message, sessionId, sender);
        } catch (ProtocolException e) {
            return reject(e.code());
        }
        ObjectNode ignored = ignoreInactiveMessage(message);
        if (ignored != null) {
            return ignored;
        }
        return switch (message.path("type").asText()) {
            case "ready" -> acceptReady();
            case "shutdown" -> acceptShutdown();
            case "run" -> acceptRun(message.path("requestId").asText());
            case "cancel" -> acceptCancel();
            case "operation_request" -> acceptOperationRequest(message.path("payload"));
            case "operation_result" -> acceptOperationResult(message.path("payload"));
            case "completed" -> acceptCompleted();
            case "failed" -> acceptFailed(message.path("payload").path("error"));
            default -> reject("INVALID_MESSAGE"); // The schema rejects unknown types first.
        };
    }

    /** Control messages bypass request guards; late data cannot change a newer run. */
    private ObjectNode ignoreInactiveMessage(JsonNode message) {
        String kind = message.path("type").asText();
        if (Set.of("ready", "shutdown", "run").contains(kind)) {
            return null;
        }
        String request = message.path("requestId").asText();
        if (!request.equals(active) || finished.contains(request)) {
            return ignore(Set.of("completed", "failed").contains(kind) && finished.contains(request)
                    ? "DUPLICATE_MESSAGE" : "REQUEST_NOT_ACTIVE");
        }
        if (Set.of("operation_request", "completed").contains(kind) && state.equals("CANCELLING")) {
            return ignore("REQUEST_NOT_ACTIVE");
        }
        return null;
    }

    private ObjectNode acceptReady() {
        if (!state.equals("STARTING")) {
            return reject("INVALID_SEQUENCE");
        }
        state = "IDLE";
        return decision();
    }

    private ObjectNode acceptShutdown() {
        if (state.equals("CLOSED")) {
            return reject("INVALID_SEQUENCE");
        }
        state = "STOPPING";
        return decision();
    }

    private ObjectNode acceptRun(String request) {
        if (requests.contains(request)) {
            return reject("DUPLICATE_MESSAGE");
        }
        if (!state.equals("IDLE") || pendingId != null) {
            return reject("INVALID_SEQUENCE");
        }
        requests.add(request);
        active = request;
        operations.clear();
        resolved.clear();
        terminal = awaitingHistory = failedOperation = false;
        committed = uncertain = false;
        state = "RUNNING";
        return decision();
    }

    private ObjectNode acceptCancel() {
        if (terminal) {
            return ignore("REQUEST_NOT_ACTIVE");
        }
        state = "CANCELLING";
        return decision();
    }

    private ObjectNode acceptOperationRequest(JsonNode payload) {
        String id = payload.path("operationId").asText();
        if (operations.contains(id)) {
            return reject("DUPLICATE_MESSAGE");
        }
        if (!state.equals("RUNNING") || pendingId != null || terminal || failedOperation) {
            return reject("INVALID_SEQUENCE");
        }
        operations.add(id);
        pendingId = id;
        pendingOperation = payload.path("operation").asText();
        uncertain = WRITES.contains(pendingOperation);
        dispatchCount++;
        return decision().put("dispatch", pendingOperation);
    }

    private ObjectNode acceptOperationResult(JsonNode payload) {
        String id = payload.path("operationId").asText();
        String operation = payload.path("operation").asText();
        if (resolved.contains(id)) {
            return reject("DUPLICATE_MESSAGE");
        }
        if (!id.equals(pendingId) || !operation.equals(pendingOperation)) {
            return reject("OPERATION_NOT_PENDING");
        }
        resolved.add(id);
        pendingId = pendingOperation = null;
        recordOperationResult(payload);
        return decision();
    }

    private void recordOperationResult(JsonNode payload) {
        if (payload.path("outcome").asText().equals("success")) {
            committed |= WRITES.contains(payload.path("operation").asText());
            uncertain = false;
        } else {
            failedOperation = true;
            recordWriteOutcome(payload.path("error").path("writeOutcome").asText());
        }
    }

    /** A later failure or cancellation must not erase a previously confirmed write. */
    private void recordWriteOutcome(String outcome) {
        uncertain = outcome.equals("unknown");
        committed |= outcome.equals("committed");
    }

    private ObjectNode acceptCompleted() {
        if (!state.equals("RUNNING") || pendingId != null || failedOperation || terminal) {
            return reject("INVALID_SEQUENCE");
        }
        terminal = awaitingHistory = true;
        return decision().put("historyAppend", 1);
    }

    private ObjectNode acceptFailed(JsonNode error) {
        if (terminal || !Set.of("RUNNING", "CANCELLING").contains(state)) {
            return reject("INVALID_SEQUENCE");
        }
        if (pendingId != null && !state.equals("CANCELLING")
                && !error.path("code").asText().equals("DEADLINE_EXCEEDED")) {
            return reject("INVALID_SEQUENCE");
        }
        terminal = true;
        if (pendingId != null) {
            state = "CANCELLING";
        }
        settleRun();
        int status = switch (error.path("category").asText()) {
            case "validation" -> 400;
            case "authentication" -> 401;
            default -> 500;
        };
        return decision().put("httpStatus", status);
    }

    /** Local service/history outcomes are supplied by their owner, never inferred from worker exit. */
    public ObjectNode observe(String event, String outcome, String operationId) {
        return switch (event) {
            case "deadline" -> observeDeadline();
            case "cancel_grace_elapsed" -> observeCancelGraceElapsed();
            case "pipe_eof" -> observePipeEof();
            case "operation_settled" -> observeOperationSettled(operationId, outcome);
            case "history_settled" -> observeHistorySettled(outcome);
            default -> reject("INVALID_SEQUENCE");
        };
    }

    private ObjectNode observeDeadline() {
        if (!state.equals("RUNNING") || terminal) {
            return ignore("REQUEST_NOT_ACTIVE");
        }
        state = "CANCELLING";
        return decision().put("httpStatus", 500);
    }

    private ObjectNode observeCancelGraceElapsed() {
        if (!state.equals("CANCELLING")) {
            return reject("INVALID_SEQUENCE");
        }
        state = "STOPPING";
        return decision().put("terminateWorker", true);
    }

    private ObjectNode observePipeEof() {
        state = "CLOSED";
        return decision().put("httpStatus", 500);
    }

    /** Retain service outcomes after cancellation/disconnect without restarting worker tools. */
    private ObjectNode observeOperationSettled(String operationId, String outcome) {
        if (!operationId.equals(pendingId)) {
            return reject("OPERATION_NOT_PENDING");
        }
        resolved.add(operationId);
        pendingId = pendingOperation = null;
        recordWriteOutcome(outcome);
        if (terminal) {
            settleRun();
        }
        return decision().put("sendResult", false);
    }

    private ObjectNode observeHistorySettled(String outcome) {
        if (!awaitingHistory) {
            return reject("INVALID_SEQUENCE");
        }
        awaitingHistory = false;
        settleRun();
        ObjectNode result = decision();
        if (outcome.equals("failure")) {
            result.put("code", "HISTORY_FAILURE").put("httpStatus", 500);
        }
        return result;
    }
}
