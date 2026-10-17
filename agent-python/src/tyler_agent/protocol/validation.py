"""Shared protocol validation and safe diagnostics, independent of codecs and receivers."""

import json
import re
from decimal import Decimal
from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import ValidationError, best_match

from tyler_agent.resources import resource_root

type Message = dict[str, Any]
MAX_FRAME_BYTES = 16_777_216
MAX_DECIMAL_VALUE = 2_000_000_000
DECIMAL_FIELDS = {"amount", "calories", "protein", "carbs", "fat", "fiber", "weight", "Age"}


class ProtocolError(ValueError):
    """Stable machine code plus a developer-facing explanation without payload values."""

    def __init__(self, code: str, detail: str) -> None:
        # Callers supply code-owned explanations, never parser/worker messages or raw values.
        self.code = code
        self.detail = detail
        super().__init__(f"{code}: {detail}")


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


def validate_frame_size(size: int) -> None:
    """Apply the same size limit to complete and incrementally buffered frames."""
    if size > MAX_FRAME_BYTES:
        raise ProtocolError("MESSAGE_TOO_LARGE", "Frame exceeds the 16777216-byte limit")


def validate_session_id(session_id: str) -> None:
    """Reject an invalid startup identity before announcing worker readiness."""
    if not re.fullmatch("[a-f0-9]{32}", session_id):
        raise ProtocolError(
            "SESSION_MISMATCH", "Session identifier does not match the worker session"
        )


def validate_frame(frame: bytes) -> None:
    validate_frame_size(len(frame))
    if not frame:
        raise ProtocolError("INVALID_FRAME", "Empty frame")
    if frame.startswith(b"\xef\xbb\xbf"):
        raise ProtocolError("INVALID_FRAME", "UTF-8 byte order mark is not allowed")
    if frame.endswith(b"\r"):
        raise ProtocolError("INVALID_FRAME", "CRLF termination is not allowed; use LF")


class MessageValidator:
    """Own shared wire rules; domain rules and lifecycle state remain with their services."""

    def __init__(self) -> None:
        schema = json.loads(
            (resource_root() / "contracts/agent/v1/message.schema.json").read_text("utf-8")
        )
        Draft202012Validator.check_schema(schema)
        self._validator = Draft202012Validator(schema)
        self._message_branches = schema["oneOf"]
        self._operations = frozenset(schema["$defs"]["operation"]["enum"])

    def validate_shape(self, value: Any) -> Message:
        """Check envelope precedence, then report a safe schema diagnostic."""
        self._validate_version(value)
        self._validate_operation(value)
        error = next(self._validator.iter_errors(value), None)
        if error is not None:
            error = self._select_message_error(error, value)
            raise ProtocolError("INVALID_MESSAGE", self._schema_error_detail(error))
        if not isinstance(value, dict):
            raise ProtocolError("INVALID_MESSAGE", "Message must be a JSON object")
        return value

    def _validate_version(self, value: Any) -> None:
        if not isinstance(value, dict) or "protocolVersion" not in value:
            return
        version = value["protocolVersion"]
        if isinstance(version, bool) or version != 1:
            raise ProtocolError("UNSUPPORTED_VERSION", "protocolVersion must be 1")

    def _validate_operation(self, value: Any) -> None:
        if not isinstance(value, dict) or value.get("type") not in (
            "operation_request",
            "operation_result",
        ):
            return
        payload = value.get("payload")
        if not isinstance(payload, dict):
            return
        operation = payload.get("operation")
        if isinstance(operation, str) and operation not in self._operations:
            raise ProtocolError(
                "UNKNOWN_OPERATION", "payload.operation is not an allowed operation"
            )

    def _select_message_error(self, error: ValidationError, value: Any) -> ValidationError:
        """Explain the known message branch instead of unrelated union branches."""
        if error.validator != "oneOf" or error.path or not isinstance(value, dict):
            return error
        for index, branch in enumerate(self._message_branches):
            if branch["properties"]["type"]["const"] == value.get("type"):
                return best_match(e for e in error.context if e.schema_path[0] == index) or error
        return error

    def _schema_error_detail(self, error: ValidationError) -> str:
        """Use trusted schema paths and names, never peer values or raw validator messages."""
        location = "/" + "/".join(str(part) for part in error.absolute_schema_path)
        detail = f"Schema rule '{error.validator}' failed at {location}"
        if (
            error.validator == "required"
            and isinstance(error.instance, dict)
            and isinstance(error.validator_value, list)
        ):
            missing = [name for name in error.validator_value if name not in error.instance]
            detail += "; missing required fields: " + ", ".join(missing)
        return detail

    def validate_semantics(self, message: Message) -> None:
        """Do not interpret dates or reject negative domain values in the transport."""
        payload = message["payload"]
        if message["type"] == "run":
            self._validate_run_values(payload)
        self._numbers(payload)

    def _validate_run_values(self, payload: Message) -> None:
        for field in ("model", "apiKey"):
            if not payload[field].strip():
                raise ProtocolError("INVALID_VALUE", f"payload.{field} must not be blank")
        if payload["operationTimeoutMs"] > payload["timeoutMs"]:
            raise ProtocolError(
                "INVALID_VALUE", "payload.operationTimeoutMs must not exceed timeoutMs"
            )

    def _numbers(self, value: Any) -> None:
        """Walk nested payloads; each numeric field is checked independently."""
        if isinstance(value, dict):
            for key, item in value.items():
                self._validate_number(key, item)
                self._numbers(item)
        elif isinstance(value, list):
            for item in value:
                self._numbers(item)

    def _validate_number(self, key: str, value: Any) -> None:
        if not isinstance(value, str):
            return
        if key == "id" and int(value) > 9_223_372_036_854_775_807:
            raise ProtocolError("INVALID_VALUE", "Record id exceeds the signed 64-bit maximum")
        # The schema permits plain decimals only; compare exactly without rounding.
        if key in DECIMAL_FIELDS and Decimal(value).copy_abs() > MAX_DECIMAL_VALUE:
            raise ProtocolError(
                "INVALID_VALUE", f"{key} must have an absolute value at most 2000000000"
            )

    def validate_received(self, message: Message, session_id: str, sender: str) -> None:
        """Check shape, session, direction, then semantics in contractual order."""
        self.validate_shape(message)
        if message["sessionId"] != session_id:
            raise ProtocolError(
                "SESSION_MISMATCH", "Session identifier does not match the worker session"
            )
        if DIRECTIONS[message["type"]] != sender:
            raise ProtocolError("WRONG_DIRECTION", "Message type is not allowed from this sender")
        self.validate_semantics(message)
