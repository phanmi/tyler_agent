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
        try:
            self.codec.validate_shape(message)
            if message["sessionId"] != self.session_id:
                raise ProtocolError("SESSION_MISMATCH")
            kind = message["type"]
            if DIRECTIONS[kind] != sender:
                raise ProtocolError("WRONG_DIRECTION")
            self.codec.validate_semantics(message)
        except ProtocolError as error:
            return self._reject(error.code)
        payload = message["payload"]
        request_id = message["requestId"]
        if kind == "ready":
            if self.state != "STARTING":
                return self._reject("INVALID_SEQUENCE")
            self.state = "IDLE"
        elif kind == "shutdown":
            if self.state == "CLOSED":
                return self._reject("INVALID_SEQUENCE")
            self.state = "STOPPING"
        elif kind == "run":
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
        elif request_id != self.active or request_id in self.finished:
            reason = (
                "DUPLICATE_MESSAGE"
                if kind in {"completed", "failed"} and request_id in self.finished
                else "REQUEST_NOT_ACTIVE"
            )
            return self._decision("ignore", reason=reason)
        elif kind == "cancel":
            if self.terminal:
                return self._decision("ignore", reason="REQUEST_NOT_ACTIVE")
            self.state = "CANCELLING"
        elif kind in {"operation_request", "completed"} and self.state == "CANCELLING":
            return self._decision("ignore", reason="REQUEST_NOT_ACTIVE")
        elif kind == "operation_request":
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
        elif kind == "operation_result":
            operation_id, operation = payload["operationId"], payload["operation"]
            if operation_id in self.resolved:
                return self._reject("DUPLICATE_MESSAGE")
            if self.pending != (operation_id, operation):
                return self._reject("OPERATION_NOT_PENDING")
            self.resolved.add(operation_id)
            self.pending = None
            if payload["outcome"] == "success":
                self.committed |= operation in WRITES
                self.uncertain = False
            else:
                self.failed_operation = True
                outcome = payload["error"]["writeOutcome"]
                self.uncertain = outcome == "unknown"
                self.committed |= outcome == "committed"
        elif kind == "completed":
            if self.state != "RUNNING" or self.pending or self.failed_operation or self.terminal:
                return self._reject("INVALID_SEQUENCE")
            self.terminal = self.awaiting_history = True
            return self._decision(historyAppend=1)
        elif kind == "failed":
            code = payload["error"]["code"]
            if self.terminal or self.state not in {"RUNNING", "CANCELLING"}:
                return self._reject("INVALID_SEQUENCE")
            if self.pending and self.state != "CANCELLING" and code != "DEADLINE_EXCEEDED":
                return self._reject("INVALID_SEQUENCE")
            self.terminal = True
            if self.pending:
                self.state = "CANCELLING"
            self._settle_run()
            return self._decision(
                httpStatus=400
                if payload["error"]["category"] == "validation"
                else 401
                if payload["error"]["category"] == "authentication"
                else 500
            )
        return self._decision()

    def observe(self, event: str, *, outcome: str = "", operation_id: str = "") -> Message:
        """Only the owner of Java work may report settlement; cancellation is not settlement."""
        if event == "deadline":
            if self.state != "RUNNING" or self.terminal:
                return self._decision("ignore", reason="REQUEST_NOT_ACTIVE")
            self.state = "CANCELLING"
            return self._decision(httpStatus=500)
        if event == "cancel_grace_elapsed":
            if self.state != "CANCELLING":
                return self._reject("INVALID_SEQUENCE")
            self.state = "STOPPING"
            return self._decision(terminateWorker=True)
        if event == "pipe_eof":
            self.state = "CLOSED"
            return self._decision(httpStatus=500)
        if event == "operation_settled":
            if not self.pending or self.pending[0] != operation_id:
                return self._reject("OPERATION_NOT_PENDING")
            self.resolved.add(operation_id)
            self.pending = None
            self.uncertain = outcome == "unknown"
            self.committed |= outcome == "committed"
            if self.terminal:
                self._settle_run()
            return self._decision(sendResult=False)
        if event == "history_settled":
            if not self.awaiting_history:
                return self._reject("INVALID_SEQUENCE")
            self.awaiting_history = False
            self._settle_run()
            if outcome == "failure":
                return self._decision(code="HISTORY_FAILURE", httpStatus=500)
            return self._decision()
        return self._reject("INVALID_SEQUENCE")
