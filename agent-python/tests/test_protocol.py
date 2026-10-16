"""The committed cross-language fixtures are the conformance oracle, not copied examples."""

import base64
import copy
import json
from typing import Any

import pytest
from conftest import cases

from tyler_agent.protocol import FrameReader, MessageCodec, ProtocolError
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
    else:
        CODEC.validate_shape(message)
        if expected.get("semantic", {}).get("valid") is False:
            with pytest.raises(ProtocolError) as failure:
                CODEC.validate_semantics(message)
            assert failure.value.code == expected["semantic"]["reason"]
        else:
            encoded = CODEC.encode(message)
            assert CODEC.decode(encoded[:-1]) == message
            assert encoded[-1:] == b"\n"
            assert b"\n" not in encoded[:-1]


@pytest.mark.parametrize("case", cases("framing"), ids=lambda item: item["id"])
def test_incremental_frames(case: dict[str, Any]) -> None:
    if "generator" in case:
        generator = case["generator"]
        message = copy.deepcopy(generator["message"])
        base_size = len(json.dumps(message, separators=(",", ":")).encode())
        message["payload"]["reply"] = "x" * (generator["frameBytes"] - base_size)
        frame = json.dumps(message, separators=(",", ":")).encode()
        assert len(frame) == generator["frameBytes"]
        chunks = [frame, b"\n"]
    else:
        chunks = [base64.b64decode(value, validate=True) for value in case["chunksBase64"]]
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
    else:
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
            message = copy.deepcopy(MESSAGES[step["ref"]])
            for pointer, value in step.get("set", {}).items():
                keys = [key.replace("~1", "/").replace("~0", "~") for key in pointer[1:].split("/")]
                target = message
                for key in keys[:-1]:
                    target = target[key]
                assert keys[-1] in target
                target[keys[-1]] = value
            # Exercise the codec before the state observer, including every patched message.
            actual = session.accept(CODEC.decode(CODEC.encode(message)[:-1]), step["from"])
        assert {key: actual.get(key) for key in step["expected"]} == step["expected"]


@pytest.mark.parametrize("raw", [b'{"text":"secret-token","x":', b"\xffsecret-token\n"])
def test_parser_diagnostics_do_not_expose_input(raw: bytes) -> None:
    with pytest.raises(ProtocolError, match="^INVALID_FRAME$") as failure:
        CODEC.decode(raw)
    assert failure.value.__cause__ is None
