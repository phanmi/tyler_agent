"""Pure protocol observer for correlation and effects; performs no service or history writes.

Java owns the actual operation/history ledger. Local events here describe observed Java
outcomes so fixture conformance can be checked without a subprocess or database.
"""

from typing import Any

from tyler_agent.protocol import Message, MessageCodec, ProtocolError

DIRECTIONS = {
    "ready": "python",
    "run": "java",
    "operation_request": "python",
    "operation_result": "java",
    "completed": "python",
    "failed": "python",
    "cancel": "java",
    "shutdown": "java",
}
WRITES = {"food.insert", "workout_plan.generate_save", "file.write"}


class ProtocolSession:
    """Observe serialized messages and authoritative local completions without side effects."""

    def __init__(self, session_id: str, codec: MessageCodec) -> None:
        self.session_id = session_id
        self.codec = codec
        self.state = "STARTING"
        self.active: str | None = None
        self.requests: set[str] = set()
        self.finished: set[str] = set()
        self.operations: set[str] = set()
        self.resolved: set[str] = set()
        self.pending: tuple[str, str] | None = None
        self.committed = False
        self.uncertain = False
        self.failed_operation = False
        self.terminal = False
        self.awaiting_history = False
        self.dispatch_count = 0

    def _decision(self, action: str = "accept", **extra: Any) -> Message:
        return {
            "action": action,
            "state": self.state,
            "writeOutcome": "unknown"
            if self.uncertain
            else "committed"
            if self.committed
            else "none",
            "historyAppend": 0,
            "dispatchCount": self.dispatch_count,
            "replayCount": 0,
            "activeRequestId": self.active,
            "awaitingHistory": self.awaiting_history,
            "failedOperation": self.failed_operation,
            "laneBlocked": self.pending is not None
            and self.state in {"CANCELLING", "CLOSED", "STOPPING"},
            **extra,
        }

    def _reject(self, code: str) -> Message:
        self.state = "STOPPING"
        return self._decision("reject", reason=code)

    def _settle_run(self) -> None:
        if self.active is not None:
            self.finished.add(self.active)
        if self.pending is None and self.state not in {"STOPPING", "CLOSED"}:
            self.state = "IDLE"

    def accept(self, message: Message, sender: str) -> Message:
        """Validate first, apply shared request guards, then handle one message kind."""
        try:
            self._validate_received_message(message, sender)
        except ProtocolError as error:
            return self._reject(error.code)

        ignored = self._ignore_inactive_message(message)
        if ignored is not None:
            return ignored
        handlers = {
            "ready": self._accept_ready,
            "shutdown": self._accept_shutdown,
            "run": self._accept_run,
            "cancel": self._accept_cancel,
            "operation_request": self._accept_operation_request,
            "operation_result": self._accept_operation_result,
            "completed": self._accept_completed,
            "failed": self._accept_failed,
        }
        return handlers[message["type"]](message)

    def _validate_received_message(self, message: Message, sender: str) -> None:
        """Keep schema, session, direction, and semantic rejection precedence explicit."""
        self.codec.validate_shape(message)
        if message["sessionId"] != self.session_id:
            raise ProtocolError(
                "SESSION_MISMATCH", "Session identifier does not match the worker session"
            )
        if DIRECTIONS[message["type"]] != sender:
            raise ProtocolError("WRONG_DIRECTION", "Message type is not allowed from this sender")
        self.codec.validate_semantics(message)

    def _ignore_inactive_message(self, message: Message) -> Message | None:
        """Control messages can start a run; late request messages must not change its ledger."""
        kind, request_id = message["type"], message["requestId"]
        if kind in {"ready", "shutdown", "run"}:
            return None
        if request_id != self.active or request_id in self.finished:
            reason = (
                "DUPLICATE_MESSAGE"
                if kind in {"completed", "failed"} and request_id in self.finished
                else "REQUEST_NOT_ACTIVE"
            )
            return self._decision("ignore", reason=reason)
        if kind in {"operation_request", "completed"} and self.state == "CANCELLING":
            return self._decision("ignore", reason="REQUEST_NOT_ACTIVE")
        return None

    def _accept_ready(self, _message: Message) -> Message:
        if self.state != "STARTING":
            return self._reject("INVALID_SEQUENCE")
        self.state = "IDLE"
        return self._decision()

    def _accept_shutdown(self, _message: Message) -> Message:
        if self.state == "CLOSED":
            return self._reject("INVALID_SEQUENCE")
        self.state = "STOPPING"
        return self._decision()

    def _accept_run(self, message: Message) -> Message:
        request_id = message["requestId"]
        if request_id in self.requests:
            return self._reject("DUPLICATE_MESSAGE")
        if self.state != "IDLE" or self.pending:
            return self._reject("INVALID_SEQUENCE")
        self.requests.add(request_id)
        self.active = request_id
        self.operations.clear()
        self.resolved.clear()
        self.terminal = self.awaiting_history = self.failed_operation = False
        self.committed = self.uncertain = False
        self.state = "RUNNING"
        return self._decision()

    def _accept_cancel(self, _message: Message) -> Message:
        if self.terminal:
            return self._decision("ignore", reason="REQUEST_NOT_ACTIVE")
        self.state = "CANCELLING"
        return self._decision()

    def _accept_operation_request(self, message: Message) -> Message:
        """Record a single dispatch; a write remains uncertain until Java reports its outcome."""
        payload = message["payload"]
        operation_id, operation = payload["operationId"], payload["operation"]
        if operation_id in self.operations:
            return self._reject("DUPLICATE_MESSAGE")
        if self.state != "RUNNING" or self.pending or self.terminal or self.failed_operation:
            return self._reject("INVALID_SEQUENCE")
        self.operations.add(operation_id)
        self.pending = (operation_id, operation)
        self.uncertain = operation in WRITES
        self.dispatch_count += 1
        return self._decision(dispatch=operation)

    def _accept_operation_result(self, message: Message) -> Message:
        payload = message["payload"]
        operation_id, operation = payload["operationId"], payload["operation"]
        if operation_id in self.resolved:
            return self._reject("DUPLICATE_MESSAGE")
        if self.pending != (operation_id, operation):
            return self._reject("OPERATION_NOT_PENDING")
        self.resolved.add(operation_id)
        self.pending = None
        self._record_operation_result(payload)
        return self._decision()

    def _record_operation_result(self, payload: Message) -> None:
        if payload["outcome"] == "success":
            self.committed |= payload["operation"] in WRITES
            self.uncertain = False
        else:
            self.failed_operation = True
            self._record_write_outcome(payload["error"]["writeOutcome"])

    def _record_write_outcome(self, outcome: str) -> None:
        """A later outcome must not erase an earlier confirmed commit."""
        self.uncertain = outcome == "unknown"
        self.committed |= outcome == "committed"

    def _accept_completed(self, _message: Message) -> Message:
        if self.state != "RUNNING" or self.pending or self.failed_operation or self.terminal:
            return self._reject("INVALID_SEQUENCE")
        self.terminal = self.awaiting_history = True
        return self._decision(historyAppend=1)

    def _accept_failed(self, message: Message) -> Message:
        error = message["payload"]["error"]
        if self.terminal or self.state not in {"RUNNING", "CANCELLING"}:
            return self._reject("INVALID_SEQUENCE")
        if self.pending and self.state != "CANCELLING" and error["code"] != "DEADLINE_EXCEEDED":
            return self._reject("INVALID_SEQUENCE")
        self.terminal = True
        if self.pending:
            self.state = "CANCELLING"
        self._settle_run()
        status = {"validation": 400, "authentication": 401}.get(error["category"], 500)
        return self._decision(httpStatus=status)

    def observe(self, event: str, *, outcome: str = "", operation_id: str = "") -> Message:
        """Route authoritative Java observations; cancellation alone is not settlement."""
        match event:
            case "deadline":
                return self._observe_deadline()
            case "cancel_grace_elapsed":
                return self._observe_cancel_grace_elapsed()
            case "pipe_eof":
                return self._observe_pipe_eof()
            case "operation_settled":
                return self._observe_operation_settled(operation_id, outcome)
            case "history_settled":
                return self._observe_history_settled(outcome)
            case _:
                return self._reject("INVALID_SEQUENCE")

    def _observe_deadline(self) -> Message:
        if self.state != "RUNNING" or self.terminal:
            return self._decision("ignore", reason="REQUEST_NOT_ACTIVE")
        self.state = "CANCELLING"
        return self._decision(httpStatus=500)

    def _observe_cancel_grace_elapsed(self) -> Message:
        if self.state != "CANCELLING":
            return self._reject("INVALID_SEQUENCE")
        self.state = "STOPPING"
        return self._decision(terminateWorker=True)

    def _observe_pipe_eof(self) -> Message:
        self.state = "CLOSED"
        return self._decision(httpStatus=500)

    def _observe_operation_settled(self, operation_id: str, outcome: str) -> Message:
        """Settle local work after disconnect without resuming the worker's tool loop."""
        if not self.pending or self.pending[0] != operation_id:
            return self._reject("OPERATION_NOT_PENDING")
        self.resolved.add(operation_id)
        self.pending = None
        self._record_write_outcome(outcome)
        if self.terminal:
            self._settle_run()
        return self._decision(sendResult=False)

    def _observe_history_settled(self, outcome: str) -> Message:
        if not self.awaiting_history:
            return self._reject("INVALID_SEQUENCE")
        self.awaiting_history = False
        self._settle_run()
        if outcome == "failure":
            return self._decision(code="HISTORY_FAILURE", httpStatus=500)
        return self._decision()
