"""Version 1 wire validation, independent of models, domain rules, and process lifecycle."""

import json
from collections.abc import Iterator
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


def _unique_object(pairs: list[tuple[str, Any]]) -> Message:
    result: Message = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("INVALID_FRAME", "Duplicate JSON object key")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ProtocolError("INVALID_FRAME", "NaN and Infinity are not allowed in JSON")


def _strings(value: Any) -> Iterator[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, item in value.items():
            yield key
            yield from _strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)


class MessageCodec:
    """Validate a closed schema and transport semantics before any service dispatch."""

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

    def decode(self, frame: bytes) -> Message:
        """Decode framing and shape only; the receiver checks session/direction before semantics."""
        self._validate_frame(frame)
        return self.validate_shape(self._parse_json(frame))

    def _validate_frame(self, frame: bytes) -> None:
        if len(frame) > MAX_FRAME_BYTES:
            raise ProtocolError("MESSAGE_TOO_LARGE", "Frame exceeds the 16777216-byte limit")
        if not frame:
            raise ProtocolError("INVALID_FRAME", "Empty frame")
        if frame.startswith(b"\xef\xbb\xbf"):
            raise ProtocolError("INVALID_FRAME", "UTF-8 byte order mark is not allowed")
        if frame.endswith(b"\r"):
            raise ProtocolError("INVALID_FRAME", "CRLF termination is not allowed; use LF")

    def _parse_json(self, frame: bytes) -> Any:
        """Normalize parser failures without retaining raw JSON in the displayed exception."""
        try:
            value = json.loads(
                frame.decode("utf-8", errors="strict"),
                object_pairs_hook=_unique_object,
                parse_constant=_reject_constant,
            )
            if any(any(0xD800 <= ord(char) <= 0xDFFF for char in text) for text in _strings(value)):
                raise ProtocolError("INVALID_FRAME", "JSON contains an unpaired Unicode surrogate")
        except ProtocolError:
            raise
        except UnicodeError:
            raise ProtocolError("INVALID_FRAME", "Frame is not valid UTF-8") from None
        except json.JSONDecodeError as error:
            raise ProtocolError(
                "INVALID_FRAME", f"Invalid JSON syntax at line {error.lineno}, column {error.colno}"
            ) from None
        except RecursionError:
            raise ProtocolError("INVALID_FRAME", "JSON nesting exceeds the decoder limit") from None
        except ValueError:
            raise ProtocolError("INVALID_FRAME", "JSON number exceeds the parser limit") from None
        return value

    def encode(self, message: Message) -> bytes:
        """Reject invalid locally generated values before writing a frame to the peer."""
        frame = self._serialize_json(message)
        self.validate_semantics(self.decode(frame))
        return frame + b"\n"

    def _serialize_json(self, message: Message) -> bytes:
        try:
            return json.dumps(
                message, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode("utf-8")
        except UnicodeError:
            raise ProtocolError("INVALID_FRAME", "Message contains invalid Unicode") from None
        except TypeError:
            raise ProtocolError(
                "INVALID_FRAME", "Message contains a type that JSON cannot serialize"
            ) from None
        except RecursionError:
            raise ProtocolError(
                "INVALID_FRAME", "Message nesting exceeds the encoder limit"
            ) from None
        except ValueError:
            raise ProtocolError(
                "INVALID_FRAME", "Message contains a circular reference or non-finite number"
            ) from None


class FrameReader:
    """Return shape-validated frames; session/direction and semantics remain receiver checks."""

    def __init__(self, codec: MessageCodec) -> None:
        self._codec = codec
        self._pending = bytearray()

    def feed(self, chunk: bytes) -> list[Message]:
        messages: list[Message] = []
        start = 0
        while start < len(chunk):
            end = chunk.find(b"\n", start)
            stop = len(chunk) if end < 0 else end
            self._append_chunk(chunk, start, stop)
            if end < 0:
                break
            messages.append(self._codec.decode(bytes(self._pending)))
            self._pending.clear()
            start = end + 1
        return messages

    def _append_chunk(self, chunk: bytes, start: int, stop: int) -> None:
        if len(self._pending) + stop - start > MAX_FRAME_BYTES:
            raise ProtocolError("MESSAGE_TOO_LARGE", "Frame exceeds the 16777216-byte limit")
        self._pending.extend(chunk[start:stop])

    def finish(self) -> None:
        if self._pending:
            raise ProtocolError("INVALID_FRAME", "Input ended before the frame's terminating LF")
