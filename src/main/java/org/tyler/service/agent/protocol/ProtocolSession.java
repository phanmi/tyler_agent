package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure serialized protocol state and effect ledger. It authorizes no actual service calls.
 * The future supervisor must dispatch only accepted operations and report real local outcomes.
 */
public final class ProtocolSession {
    private static final Map<String, String> DIRECTIONS = Map.of("ready", "python", "run", "java",
            "operation_request", "python", "operation_result", "java", "completed", "python",
            "failed", "python", "cancel", "java", "shutdown", "java");
    private static final Set<String> WRITES = Set.of("food.insert", "workout_plan.generate_save", "file.write");
    private final String sessionId;
    private final MessageCodec codec;
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
        this.codec = codec;
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

    /** Validate direction and correlation before changing the operation ledger. */
    public ObjectNode accept(JsonNode message, String sender) {
        try {
            codec.validateShape(message);
            if (!sessionId.equals(message.path("sessionId").asText())) {
                throw new ProtocolException("SESSION_MISMATCH");
            }
            if (!sender.equals(DIRECTIONS.get(message.path("type").asText()))) {
                throw new ProtocolException("WRONG_DIRECTION");
            }
            codec.validateSemantics(message);
        } catch (ProtocolException e) {
            return reject(e.code());
        }
        String kind = message.path("type").asText();
        String request = message.path("requestId").asText();
        JsonNode payload = message.path("payload");
        if (kind.equals("ready")) {
            if (!state.equals("STARTING")) {
                return reject("INVALID_SEQUENCE");
            }
            state = "IDLE";
        } else if (kind.equals("shutdown")) {
            if (state.equals("CLOSED")) {
                return reject("INVALID_SEQUENCE");
            }
            state = "STOPPING";
        } else if (kind.equals("run")) {
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
        } else if (!request.equals(active) || finished.contains(request)) {
            return ignore(Set.of("completed", "failed").contains(kind) && finished.contains(request)
                    ? "DUPLICATE_MESSAGE" : "REQUEST_NOT_ACTIVE");
        } else if (kind.equals("cancel")) {
            if (terminal) {
                return ignore("REQUEST_NOT_ACTIVE");
            }
            state = "CANCELLING";
        } else if (Set.of("operation_request", "completed").contains(kind) && state.equals("CANCELLING")) {
            return ignore("REQUEST_NOT_ACTIVE");
        } else if (kind.equals("operation_request")) {
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
        } else if (kind.equals("operation_result")) {
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
            if (payload.path("outcome").asText().equals("success")) {
                committed |= WRITES.contains(operation);
                uncertain = false;
            } else {
                failedOperation = true;
                String outcome = payload.path("error").path("writeOutcome").asText();
                uncertain = outcome.equals("unknown");
                committed |= outcome.equals("committed");
            }
        } else if (kind.equals("completed")) {
            if (!state.equals("RUNNING") || pendingId != null || failedOperation || terminal) {
                return reject("INVALID_SEQUENCE");
            }
            terminal = awaitingHistory = true;
            return decision().put("historyAppend", 1);
        } else if (kind.equals("failed")) {
            String code = payload.path("error").path("code").asText();
            if (terminal || !Set.of("RUNNING", "CANCELLING").contains(state)) {
                return reject("INVALID_SEQUENCE");
            }
            if (pendingId != null && !state.equals("CANCELLING") && !code.equals("DEADLINE_EXCEEDED")) {
                return reject("INVALID_SEQUENCE");
            }
            terminal = true;
            if (pendingId != null) {
                state = "CANCELLING";
            }
            settleRun();
            String category = payload.path("error").path("category").asText();
            return decision().put("httpStatus", category.equals("validation") ? 400 : category.equals("authentication") ? 401 : 500);
        }
        return decision();
    }

    /** Local service/history outcomes are supplied by their owner, never inferred from worker exit. */
    public ObjectNode observe(String event, String outcome, String operationId) {
        switch (event) {
            case "deadline" -> {
                if (!state.equals("RUNNING") || terminal) {
                    return ignore("REQUEST_NOT_ACTIVE");
                }
                state = "CANCELLING";
                return decision().put("httpStatus", 500);
            }
            case "cancel_grace_elapsed" -> {
                if (!state.equals("CANCELLING")) {
                    return reject("INVALID_SEQUENCE");
                }
                state = "STOPPING";
                return decision().put("terminateWorker", true);
            }
            case "pipe_eof" -> {
                state = "CLOSED";
                return decision().put("httpStatus", 500);
            }
            case "operation_settled" -> {
                if (!operationId.equals(pendingId)) {
                    return reject("OPERATION_NOT_PENDING");
                }
                resolved.add(operationId);
                pendingId = pendingOperation = null;
                uncertain = outcome.equals("unknown");
                committed |= outcome.equals("committed");
                if (terminal) {
                    settleRun();
                }
                return decision().put("sendResult", false);
            }
            case "history_settled" -> {
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
            default -> { return reject("INVALID_SEQUENCE"); }
        }
    }
}
