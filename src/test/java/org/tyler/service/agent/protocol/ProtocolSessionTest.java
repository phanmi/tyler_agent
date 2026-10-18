package org.tyler.service.agent.protocol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise public session decisions around cancellation, terminal outcomes, and local settlement. */
class ProtocolSessionTest {
    private ProtocolSession session;

    @BeforeEach
    void startRun() {
        ObjectNode ready = ProtocolTestFixtures.message("ready");
        session = new ProtocolSession(ready.path("sessionId").asText(), new MessageCodec());
        assertEquals("STARTING", session.state());
        assertEquals("IDLE", session.accept(ready, "python").path("state").asText());
        assertEquals("RUNNING", accept("run", "java").path("state").asText());
    }

    private ObjectNode accept(String fixture, String sender) {
        return session.accept(ProtocolTestFixtures.message(fixture), sender);
    }

    private void assertIgnored(ObjectNode result, String state, String reason) {
        assertEquals("ignore", result.path("action").asText());
        assertEquals(state, result.path("state").asText());
        assertEquals(reason, result.path("reason").asText());
        assertEquals(0, result.path("historyAppend").asInt());
        assertEquals(0, result.path("replayCount").asInt());
    }

    @Test
    void cancelAfterCompletionDoesNotInterruptHistory() {
        ObjectNode completed = accept("completed", "python");
        assertEquals(1, completed.path("historyAppend").asInt());
        ObjectNode ignored = accept("cancel", "java");
        assertIgnored(ignored, "RUNNING", "REQUEST_NOT_ACTIVE");
        assertTrue(ignored.path("awaitingHistory").asBoolean());
        assertEquals("IDLE", session.observe("history_settled", "success", "").path("state").asText());
    }

    @Test
    void completionAfterCancellationCannotAppendHistory() {
        accept("cancel", "java");
        ObjectNode repeated = accept("cancel", "java");
        assertEquals("accept", repeated.path("action").asText());
        ObjectNode ignored = accept("completed", "python");
        assertIgnored(ignored, "CANCELLING", "REQUEST_NOT_ACTIVE");
        assertFalse(ignored.path("awaitingHistory").asBoolean());
    }

    @ParameterizedTest
    @CsvSource({"failed_validation, 400", "failed_authentication, 401", "failed_provider, 500"})
    void failureMappingAndDuplicateTerminal(String fixture, int status) {
        ObjectNode failure = accept(fixture, "python");
        assertEquals(status, failure.path("httpStatus").asInt());
        assertEquals("IDLE", failure.path("state").asText());
        ObjectNode repeated = accept(fixture, "python");
        assertIgnored(repeated, "IDLE", "DUPLICATE_MESSAGE");
        assertFalse(repeated.has("httpStatus"));
    }

    @ParameterizedTest
    @CsvSource({"unknown_event, INVALID_SEQUENCE", "cancel_grace_elapsed, INVALID_SEQUENCE",
            "history_settled, INVALID_SEQUENCE", "operation_settled, OPERATION_NOT_PENDING"})
    void rejectsInvalidLocalObservationsWithoutDispatch(String event, String reason) {
        ObjectNode result = session.observe(event, "success", "op-missing");
        assertEquals("reject", result.path("action").asText());
        assertEquals(reason, result.path("reason").asText());
        assertEquals("STOPPING", result.path("state").asText());
        assertEquals(0, result.path("dispatchCount").asInt());
        assertEquals(0, result.path("historyAppend").asInt());
    }

    @Test
    void rejectsShutdownAfterEof() {
        assertEquals("CLOSED", session.observe("pipe_eof", "", "").path("state").asText());
        ObjectNode rejected = accept("shutdown", "java");
        assertEquals("reject", rejected.path("action").asText());
        assertEquals("INVALID_SEQUENCE", rejected.path("reason").asText());
    }

    @Test
    void invalidPeerSessionIsRejectedBeforeAnyDispatch() {
        ObjectNode message = ProtocolTestFixtures.message("food_request");
        message.put("sessionId", "f".repeat(32));
        ObjectNode result = session.accept(message, "python");
        assertEquals("reject", result.path("action").asText());
        assertEquals("SESSION_MISMATCH", result.path("reason").asText());
        assertEquals(0, result.path("dispatchCount").asInt());
        assertEquals("none", result.path("writeOutcome").asText());
    }

    @Test
    void lateOperationResultCannotChangeTheNextRun() {
        accept("failed_provider", "python");
        ObjectNode nextRun = ProtocolTestFixtures.message("run");
        nextRun.put("requestId", "req-next");
        session.accept(nextRun, "java");
        ObjectNode ignored = accept("food_result", "java");
        assertIgnored(ignored, "RUNNING", "REQUEST_NOT_ACTIVE");
        assertEquals("req-next", ignored.path("activeRequestId").asText());
        assertEquals("none", ignored.path("writeOutcome").asText());
        assertEquals(0, ignored.path("dispatchCount").asInt());
    }

    @Test
    void eofRetainsAnUnresolvedWriteUntilAuthoritativeSettlement() {
        ObjectNode request = ProtocolTestFixtures.message("food_request");
        session.accept(request, "python");
        ObjectNode closed = session.observe("pipe_eof", "", "");
        assertTrue(closed.path("laneBlocked").asBoolean());
        assertEquals("unknown", closed.path("writeOutcome").asText());
        ObjectNode settled = session.observe("operation_settled", "committed",
                request.path("payload").path("operationId").asText());
        assertEquals("CLOSED", settled.path("state").asText());
        assertEquals("committed", settled.path("writeOutcome").asText());
        assertFalse(settled.path("sendResult").asBoolean());
        assertFalse(settled.path("laneBlocked").asBoolean());
        assertEquals(1, settled.path("dispatchCount").asInt());
    }

    @Test
    void historyFailurePreservesCommittedToolsWithoutReplay() {
        accept("food_request", "python");
        accept("food_result", "java");
        accept("completed", "python");
        ObjectNode failure = session.observe("history_settled", "failure", "");
        assertEquals("IDLE", failure.path("state").asText());
        assertEquals("HISTORY_FAILURE", failure.path("code").asText());
        assertEquals(500, failure.path("httpStatus").asInt());
        assertEquals("committed", failure.path("writeOutcome").asText());
        assertEquals(1, failure.path("dispatchCount").asInt());
        assertEquals(0, failure.path("replayCount").asInt());
        assertEquals(0, failure.path("historyAppend").asInt());
    }
}
