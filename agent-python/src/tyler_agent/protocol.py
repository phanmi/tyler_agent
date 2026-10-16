"""Version 1 wire validation, independent of models, domain rules, and process lifecycle."""

import json
import re
from collections.abc import Iterator
from typing import Any

from jsonschema import Draft202012Validator

from tyler_agent.resources import resource_root

type Message = dict[str, Any]
MAX_FRAME_BYTES = 16_777_216


class ProtocolError(ValueError):
    """A safe protocol reason; raw frames and parser diagnostics must never be logged."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


def _unique_object(pairs: list[tuple[str, Any]]) -> Message:
    result: Message = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("INVALID_FRAME")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    raise ProtocolError("INVALID_FRAME")


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
        self._operations = frozenset(schema["$defs"]["operation"]["enum"])

    def validate_shape(self, value: Any) -> Message:
        if isinstance(value, dict):
            if "protocolVersion" in value and (
                isinstance(value["protocolVersion"], bool) or value["protocolVersion"] != 1
            ):
                raise ProtocolError("UNSUPPORTED_VERSION")
            payload = value.get("payload")
            if value.get("type") in ("operation_request", "operation_result") and isinstance(
                payload, dict
            ):
                operation = payload.get("operation")
                if isinstance(operation, str) and operation not in self._operations:
                    raise ProtocolError("UNKNOWN_OPERATION")
        if not self._validator.is_valid(value):
            raise ProtocolError("INVALID_MESSAGE")
        if not isinstance(value, dict):
            raise ProtocolError("INVALID_MESSAGE")
        return value

    def validate_semantics(self, message: Message) -> None:
        """Do not interpret dates or reject negative domain values in the transport."""
        payload = message["payload"]
        if message["type"] == "run":
            if not payload["model"].strip() or not payload["apiKey"].strip():
                raise ProtocolError("INVALID_VALUE")
            if payload["operationTimeoutMs"] > payload["timeoutMs"]:
                raise ProtocolError("INVALID_VALUE")
        self._numbers(payload)

    def _numbers(self, value: Any) -> None:
        if isinstance(value, dict):
            for key, item in value.items():
                if key == "id" and isinstance(item, str) and int(item) > 9_223_372_036_854_775_807:
                    raise ProtocolError("INVALID_VALUE")
                if key in {
                    "amount",
                    "calories",
                    "protein",
                    "carbs",
                    "fat",
                    "fiber",
                    "weight",
                    "Age",
                } and isinstance(item, str):
                    # Java 26 bounds the resulting scale, including exponents above INT_MAX.
                    mantissa, *exponents = re.split("[eE]", item)
                    digits = exponents[0].lstrip("+-").lstrip("0") if exponents else ""
                    if len(digits) > 10:
                        raise ProtocolError("INVALID_VALUE")
                    exponent = int(digits or "0")
                    if exponents and exponents[0].startswith("-"):
                        exponent = -exponent
                    scale = len(mantissa.split(".")[1]) if "." in mantissa else 0
                    if not -2_147_483_648 <= scale - exponent <= 2_147_483_647:
                        raise ProtocolError("INVALID_VALUE")
                self._numbers(item)
        elif isinstance(value, list):
            for item in value:
                self._numbers(item)

    def decode(self, frame: bytes) -> Message:
        """Decode bytes before LF; errors contain only allowlisted reason codes."""
        if len(frame) > MAX_FRAME_BYTES:
            raise ProtocolError("MESSAGE_TOO_LARGE")
        if not frame or frame.startswith(b"\xef\xbb\xbf") or frame.endswith(b"\r"):
            raise ProtocolError("INVALID_FRAME")
        try:
            value = json.loads(
                frame.decode("utf-8", errors="strict"),
                object_pairs_hook=_unique_object,
                parse_constant=_reject_constant,
            )
            if any(any(0xD800 <= ord(char) <= 0xDFFF for char in text) for text in _strings(value)):
                raise ProtocolError("INVALID_FRAME")
        except (UnicodeError, json.JSONDecodeError, RecursionError, ValueError) as error:
            if isinstance(error, ProtocolError):
                raise
            raise ProtocolError("INVALID_FRAME") from None
        message = self.validate_shape(value)
        self.validate_semantics(message)
        return message

    def encode(self, message: Message) -> bytes:
        try:
            frame = json.dumps(
                message, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode("utf-8")
        except (ValueError, UnicodeError, TypeError, RecursionError):
            raise ProtocolError("INVALID_FRAME") from None
        self.decode(frame)
        return frame + b"\n"


class FrameReader:
    """Bound incomplete frames and preserve UTF-8 sequences across arbitrary chunks."""

    def __init__(self, codec: MessageCodec) -> None:
        self._codec = codec
        self._pending = bytearray()

    def feed(self, chunk: bytes) -> list[Message]:
        messages: list[Message] = []
        start = 0
        while start < len(chunk):
            end = chunk.find(b"\n", start)
            stop = len(chunk) if end < 0 else end
            if len(self._pending) + stop - start > MAX_FRAME_BYTES:
                raise ProtocolError("MESSAGE_TOO_LARGE")
            self._pending.extend(chunk[start:stop])
            if end < 0:
                break
            messages.append(self._codec.decode(bytes(self._pending)))
            self._pending.clear()
            start = end + 1
        return messages

    def finish(self) -> None:
        if self._pending:
            raise ProtocolError("INVALID_FRAME")
