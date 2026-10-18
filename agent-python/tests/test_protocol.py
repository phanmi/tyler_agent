"""The committed cross-language fixtures are the conformance oracle, not copied examples."""

import json
from typing import Any

import pytest
from conftest import cases, frame_chunks, sequence_message

from tyler_agent.protocol.protocol import FrameReader, MessageCodec, ProtocolError
from tyler_agent.session import ProtocolSession

CODEC = MessageCodec()
MESSAGES = {case["id"]: case["message"] for case in cases("messages")}


@pytest.mark.parametrize("case", cases("messages"), ids=lambda item: item["id"])
def test_message_contract(case: dict[str, Any]) -> None:
    expected, message = case["expected"], case["message"]
    if not expected["schemaValid"]:
        with pytest.raises(ProtocolError) as failure:
            CODEC.validate_shape(message)
        assert failure.value.code == expected["reason"]
        return
    CODEC.validate_shape(message)
    if expected.get("semantic", {}).get("valid") is False:
        _assert_semantic_rejection(message, expected["semantic"]["reason"])
        return
    encoded = CODEC.encode(message)
    assert CODEC.decode(encoded[:-1]) == message
    assert encoded[-1:] == b"\n"
    assert b"\n" not in encoded[:-1]


def _assert_semantic_rejection(message: dict[str, Any], reason: str) -> None:
    assert CODEC.decode(json.dumps(message).encode()) == message
    with pytest.raises(ProtocolError) as failure:
        CODEC.validate_semantics(message)
    assert failure.value.code == reason
    with pytest.raises(ProtocolError) as outbound_failure:
        CODEC.encode(message)
    assert outbound_failure.value.code == reason


@pytest.mark.parametrize("case", cases("framing"), ids=lambda item: item["id"])
def test_incremental_frames(case: dict[str, Any]) -> None:
    chunks = frame_chunks(case)
    reader = FrameReader(CODEC)
    expected = case["expected"]
    decoded: list[dict[str, Any]] = []

    def read() -> None:
        for chunk in chunks:
            decoded.extend(reader.feed(chunk))
        if case["endOfInput"]:
            reader.finish()

    if not expected["accepted"]:
        with pytest.raises(ProtocolError) as failure:
            read()
        assert failure.value.code == expected["reason"]
        return
    read()
    if "messages" in expected:
        assert decoded == expected["messages"]
    if "messageCount" in expected:
        assert len(decoded) == expected["messageCount"]
        assert len(decoded[0]["payload"]["reply"].encode()) == expected["replyBytes"]


@pytest.mark.parametrize("case", cases("sequences"), ids=lambda item: item["id"])
def test_protocol_sequence(case: dict[str, Any]) -> None:
    session = ProtocolSession(MESSAGES["ready"]["sessionId"], CODEC)
    assert session.state == case["initialState"]
    for step in case["steps"]:
        if "event" in step:
            actual = session.observe(
                step["event"],
                outcome=step.get("outcome", ""),
                operation_id=step.get("operationId", ""),
            )
        else:
            # Raw peer bytes can violate semantics; do not pre-validate with our outbound encoder.
            message = sequence_message(step)
            reader = FrameReader(CODEC)
            decoded = reader.feed(json.dumps(message).encode() + b"\n")
            reader.finish()
            assert len(decoded) == 1
            actual = session.accept(decoded[0], step["from"])
        assert {key: actual.get(key) for key in step["expected"]} == step["expected"]


@pytest.mark.parametrize("raw", [b'{"text":"secret-token","x":', b"\xffsecret-token\n"])
def test_parser_diagnostics_do_not_expose_input(raw: bytes) -> None:
    with pytest.raises(ProtocolError, match="^INVALID_FRAME:") as failure:
        CODEC.decode(raw)
    assert failure.value.__cause__ is None
    assert "secret-token" not in str(failure.value)
