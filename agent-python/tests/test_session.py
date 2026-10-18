"""Public session behavior around terminal messages and authoritative local observations."""

from typing import Any

import pytest
from conftest import sequence_message

from tyler_agent.protocol.protocol import MessageCodec
from tyler_agent.session import ProtocolSession


@pytest.fixture
def running_session() -> ProtocolSession:
    ready = sequence_message({"ref": "ready"})
    session = ProtocolSession(ready["sessionId"], MessageCodec())
    assert session.accept(ready, "python")["state"] == "IDLE"
    assert session.accept(sequence_message({"ref": "run"}), "java")["state"] == "RUNNING"
    return session


def test_cancel_after_completion_does_not_interrupt_history(
    running_session: ProtocolSession,
) -> None:
    completed = running_session.accept(sequence_message({"ref": "completed"}), "python")
    assert completed["historyAppend"] == 1
    ignored = running_session.accept(sequence_message({"ref": "cancel"}), "java")
    assert ignored["action"] == "ignore" and ignored["state"] == "RUNNING"
    assert ignored["awaitingHistory"] is True
    assert ignored["historyAppend"] == 0
    assert running_session.observe("history_settled", outcome="success")["state"] == "IDLE"


def test_completion_after_cancel_cannot_append_history(running_session: ProtocolSession) -> None:
    running_session.accept(sequence_message({"ref": "cancel"}), "java")
    ignored = running_session.accept(sequence_message({"ref": "completed"}), "python")
    assert ignored["action"] == "ignore" and ignored["state"] == "CANCELLING"
    assert ignored["historyAppend"] == 0 and ignored["awaitingHistory"] is False


@pytest.mark.parametrize(
    "ref,status",
    [("failed_validation", 400), ("failed_authentication", 401), ("failed_provider", 500)],
)
def test_failure_mapping_and_duplicate_terminal(
    running_session: ProtocolSession, ref: str, status: int
) -> None:
    message = sequence_message({"ref": ref})
    failure = running_session.accept(message, "python")
    assert failure["httpStatus"] == status and failure["state"] == "IDLE"
    repeated = running_session.accept(message, "python")
    assert repeated["action"] == "ignore" and repeated["reason"] == "DUPLICATE_MESSAGE"
    assert "httpStatus" not in repeated and repeated["historyAppend"] == 0


@pytest.mark.parametrize(
    "event,kwargs,reason",
    [
        ("unknown_event", {}, "INVALID_SEQUENCE"),
        ("cancel_grace_elapsed", {}, "INVALID_SEQUENCE"),
        ("history_settled", {"outcome": "success"}, "INVALID_SEQUENCE"),
        ("operation_settled", {"operation_id": "op-missing"}, "OPERATION_NOT_PENDING"),
    ],
)
def test_invalid_local_observation_does_not_dispatch(
    running_session: ProtocolSession, event: str, kwargs: dict[str, Any], reason: str
) -> None:
    rejected = running_session.observe(event, **kwargs)
    assert rejected["action"] == "reject" and rejected["reason"] == reason
    assert rejected["state"] == "STOPPING"
    assert rejected["dispatchCount"] == rejected["historyAppend"] == 0


def test_shutdown_after_eof_is_rejected(running_session: ProtocolSession) -> None:
    assert running_session.observe("pipe_eof")["state"] == "CLOSED"
    rejected = running_session.accept(sequence_message({"ref": "shutdown"}), "java")
    assert rejected["action"] == "reject" and rejected["reason"] == "INVALID_SEQUENCE"
