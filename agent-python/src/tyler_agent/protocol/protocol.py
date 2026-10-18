"""JSON serialization and bounded LF framing for the private agent pipe."""

import json
from collections.abc import Iterator
from typing import Any

from tyler_agent.protocol.validation import (
    MAX_FRAME_BYTES as MAX_FRAME_BYTES,
)
from tyler_agent.protocol.validation import (
    Message as Message,
)
from tyler_agent.protocol.validation import (
    MessageValidator,
    validate_frame,
    validate_frame_size,
)
from tyler_agent.protocol.validation import (
    ProtocolError as ProtocolError,
)


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
    """Serialize frames and delegate wire validation to the shared validator."""

    def __init__(self) -> None:
        self.validator = MessageValidator()

    def validate_shape(self, value: Any) -> Message:
        """Preserve the codec entry point while sharing the validation implementation."""
        return self.validator.validate_shape(value)

    def validate_semantics(self, message: Message) -> None:
        """Validate locally produced payloads using the shared wire rules."""
        self.validator.validate_semantics(message)

    def decode(self, frame: bytes) -> Message:
        """Decode framing and shape only; the receiver checks session/direction before semantics."""
        validate_frame(frame)
        return self.validate_shape(self._parse_json(frame))

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
        validate_frame_size(len(self._pending) + stop - start)
        self._pending.extend(chunk[start:stop])

    def finish(self) -> None:
        if self._pending:
            raise ProtocolError("INVALID_FRAME", "Input ended before the frame's terminating LF")
