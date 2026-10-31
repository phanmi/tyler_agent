"""Actionable local diagnostics preserve stable codes without revealing peer data."""

import json
import traceback
from typing import Any

import pytest
from conftest import sequence_message

from tyler_agent.protocol.protocol import FrameReader, MessageCodec, ProtocolError
from tyler_agent.protocol.validation import PROTOCOL_VERSION

CODEC = MessageCodec()
SECRET = "synthetic-private-key-prompt-history-file"


@pytest.mark.parametrize(
    "frame,detail",
    [
        (b"", "Empty frame"),
        (b"\xef\xbb\xbf{}", "byte order mark"),
        (b"{}\r", "CRLF"),
        (b"\xff", "not valid UTF-8"),
        (b'{"a":1,"a":2}', "Duplicate JSON object key"),
        (b'{"a":NaN}', "NaN and Infinity"),
        (b'{"a":"\\ud800"}', "unpaired Unicode surrogate"),
        (b'{"a":}', "line 1, column 6"),
    ],
)
def test_decode_explains_frame_failures(frame: bytes, detail: str) -> None:
    with pytest.raises(ProtocolError) as failure:
        CODEC.decode(frame)
    assert failure.value.code == "INVALID_FRAME"
    assert detail in failure.value.detail
    assert str(failure.value) == f"INVALID_FRAME: {failure.value.detail}"


@pytest.mark.parametrize(
    "field,value,detail",
    [
        ("model", "   ", "payload.model must not be blank"),
        ("apiKey", "   ", "payload.apiKey must not be blank"),
        ("operationTimeoutMs", 300001, "operationTimeoutMs must not exceed timeoutMs"),
    ],
)
def test_semantic_failure_identifies_field(field: str, value: Any, detail: str) -> None:
    message = sequence_message({"ref": "run"})
    message["payload"][field] = value
    with pytest.raises(ProtocolError) as failure:
        CODEC.validate_semantics(message)
    assert failure.value.code == "INVALID_VALUE"
    assert detail in failure.value.detail


def test_numeric_failure_identifies_field_and_limit() -> None:
    message = sequence_message(
        {"ref": "food_request", "set": {"/payload/input/food/genericInfo/amount": "2000000001"}}
    )
    with pytest.raises(
        ProtocolError, match="amount must have an absolute value at most 2000000000"
    ):
        CODEC.validate_semantics(message)


def test_missing_schema_field_is_named() -> None:
    message = sequence_message({"ref": "run"})
    del message["payload"]["model"]
    with pytest.raises(ProtocolError) as failure:
        CODEC.validate_shape(message)
    assert failure.value.code == "INVALID_MESSAGE"
    assert "missing required fields: model" in failure.value.detail


def test_schema_type_failure_identifies_rule_and_field() -> None:
    message = sequence_message({"ref": "run"})
    message["payload"]["model"] = 123
    with pytest.raises(ProtocolError) as failure:
        CODEC.validate_shape(message)
    assert "'type'" in failure.value.detail
    assert "/model/type" in failure.value.detail


@pytest.mark.parametrize(
    "frame",
    [
        ('{"' + SECRET + '":1,"' + SECRET + '":2}').encode(),
        ('{"payload":"' + SECRET + '","x":}').encode(),
        json.dumps({"protocolVersion": PROTOCOL_VERSION, SECRET: SECRET}).encode(),
        json.dumps(sequence_message({"ref": "run", "set": {"/payload/model": [SECRET]}})).encode(),
    ],
)
def test_diagnostics_and_formatted_traceback_exclude_private_values(frame: bytes) -> None:
    with pytest.raises(ProtocolError) as failure:
        CODEC.decode(frame)
    assert SECRET not in str(failure.value)
    assert SECRET not in "".join(traceback.format_exception(failure.value))
    assert failure.value.__cause__ is None


@pytest.mark.parametrize(
    "value,detail",
    [(object(), "cannot serialize"), (float("nan"), "non-finite"), ("\ud800", "invalid Unicode")],
)
def test_encode_explains_serialization_failures(value: Any, detail: str) -> None:
    message = sequence_message({"ref": "completed"})
    message["payload"]["reply"] = value
    with pytest.raises(ProtocolError) as failure:
        CODEC.encode(message)
    assert failure.value.code == "INVALID_FRAME"
    assert detail in failure.value.detail


def test_incomplete_frame_explains_missing_terminator() -> None:
    reader = FrameReader(CODEC)
    reader.feed(b"{}")
    with pytest.raises(ProtocolError, match="terminating LF"):
        reader.finish()


@pytest.mark.parametrize("kind", [[], {}])
def test_nonscalar_message_type_remains_a_protocol_failure(kind: Any) -> None:
    message = sequence_message({"ref": "run"})
    message["type"] = kind
    with pytest.raises(ProtocolError) as failure:
        CODEC.validate_shape(message)
    assert failure.value.code == "INVALID_MESSAGE"
