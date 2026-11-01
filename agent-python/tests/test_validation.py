"""Shared receiver validation preserves error precedence and safe diagnostics."""

import pytest
from conftest import sequence_message

from tyler_agent.protocol.validation import MessageValidator, ProtocolError


@pytest.mark.parametrize(
    "wrong_shape,wrong_session,sender,expected",
    [
        (True, True, "python", "INVALID_MESSAGE"),
        (False, True, "python", "SESSION_MISMATCH"),
        (False, False, "python", "WRONG_DIRECTION"),
        (False, False, "java", "INVALID_VALUE"),
    ],
)
def test_received_validation_precedence(
    wrong_shape: bool, wrong_session: bool, sender: str, expected: str
) -> None:
    message = sequence_message({"ref": "run"})
    session_id = message["sessionId"]
    message["payload"]["model"] = " "
    if wrong_shape:
        del message["payload"]["apiKey"]
    if wrong_session:
        message["sessionId"] = "f" * 32
    with pytest.raises(ProtocolError) as failure:
        MessageValidator().validate_received(message, session_id, sender)
    assert failure.value.code == expected


@pytest.mark.parametrize("provider", ["openai", "anthropic", "deepseek"])
def test_run_accepts_each_supported_provider(provider: str) -> None:
    message = sequence_message({"ref": "run"})
    message["payload"]["provider"] = provider
    MessageValidator().validate_shape(message)


@pytest.mark.parametrize("provider", ["", "OpenAI", "claude", " openai", "gemini", None])
def test_run_rejects_unknown_or_null_provider(provider: str | None) -> None:
    message = sequence_message({"ref": "run"})
    message["payload"]["provider"] = provider
    with pytest.raises(ProtocolError) as failure:
        MessageValidator().validate_shape(message)
    assert failure.value.code == "INVALID_MESSAGE"


def test_run_rejects_missing_provider_and_names_it() -> None:
    message = sequence_message({"ref": "run"})
    del message["payload"]["provider"]
    with pytest.raises(ProtocolError) as failure:
        MessageValidator().validate_shape(message)
    assert failure.value.code == "INVALID_MESSAGE"
    assert "missing required fields: provider" in failure.value.detail


@pytest.mark.parametrize("reference,sender", [("run", "java"), ("ready", "python")])
def test_received_validation_accepts_both_directions(reference: str, sender: str) -> None:
    message = sequence_message({"ref": reference})
    MessageValidator().validate_received(message, message["sessionId"], sender)
