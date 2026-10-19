"""Real worker pipes with a deterministic injected provider; no production test switches."""

import json
import subprocess
import sys
from pathlib import Path
from queue import Queue
from threading import Thread

import pytest
from conftest import ROOT
from support.agent_fakes import payload
from test_worker import envelope, ready, start, stop

from tyler_agent.protocol.protocol import Message


def receive(process: subprocess.Popen[bytes]) -> Message:
    assert process.stdout is not None
    stream = process.stdout
    queue: Queue[bytes] = Queue()
    Thread(target=lambda: queue.put(stream.readline()), daemon=True).start()
    result: Message = json.loads(queue.get(timeout=10))
    return result


def send(
    process: subprocess.Popen[bytes], kind: str, value: Message, request: str | None = "req-1"
) -> None:
    assert process.stdin is not None
    process.stdin.write(envelope(kind, value, request))
    process.stdin.flush()


def launch(mode: str, tmp_path: Path) -> subprocess.Popen[bytes]:
    return start(
        [sys.executable, str(ROOT / "agent-python/tests/support/scripted_worker.py"), mode],
        tmp_path,
    )


@pytest.mark.parametrize("mode,terminal", [("tool", "completed"), ("failure", "failed")])
def test_actual_pipe_tool_callback_then_terminal(mode: str, terminal: str, tmp_path: Path) -> None:
    process = launch(mode, tmp_path)
    try:
        ready(process)
        send(process, "run", payload())
        operation = receive(process)["payload"]
        assert operation["operation"] == "file.write" and operation["input"]["content"] == "text"
        send(
            process,
            "operation_result",
            {
                "operationId": operation["operationId"],
                "operation": "file.write",
                "outcome": "success",
                "result": {"path": "test.txt"},
            },
        )
        response = receive(process)
        assert response["type"] == terminal
        if terminal == "failed":
            assert response["payload"]["error"]["writeOutcome"] == "committed"
        send(process, "shutdown", {"reason": "application_exit"}, None)
        output, errors = process.communicate(timeout=10)
        assert output == b"" and process.returncode == 0
        assert b"SYNTHETIC" not in errors
    finally:
        stop(process)


def test_actual_pipe_failed_read_preserves_earlier_write_outcome(tmp_path: Path) -> None:
    process = launch("write_then_read", tmp_path)
    try:
        ready(process)
        send(process, "run", payload())
        write = receive(process)["payload"]
        assert write["operation"] == "file.write"
        send(
            process,
            "operation_result",
            {
                "operationId": write["operationId"],
                "operation": "file.write",
                "outcome": "success",
                "result": {"path": "test.txt"},
            },
        )
        read = receive(process)["payload"]
        assert read["operation"] == "file.read"
        error = dict(
            category="storage", code="FILE_READ", message="Java-safe detail", writeOutcome="none"
        )
        send(
            process,
            "operation_result",
            {
                "operationId": read["operationId"],
                "operation": "file.read",
                "outcome": "failure",
                "error": error,
            },
        )
        response = receive(process)
        assert response["type"] == "failed" and response["requestId"] == "req-1"
        assert response["payload"]["error"] == error | {"writeOutcome": "committed"}
        send(process, "shutdown", {"reason": "application_exit"}, None)
        output, errors = process.communicate(timeout=10)
        assert output == b"" and process.returncode == 0
        assert b"SYNTHETIC" not in errors
    finally:
        stop(process)


@pytest.mark.parametrize("followup,code", [("run", 2), ("cancel", 0)])
def test_request_identity_after_completion(followup: str, code: int, tmp_path: Path) -> None:
    process = launch("reply", tmp_path)
    try:
        ready(process)
        send(process, "run", payload())
        assert receive(process)["type"] == "completed"
        send(process, followup, payload() if followup == "run" else {"reason": "user_cancelled"})
        output, errors = process.communicate(timeout=10)
        assert process.returncode == code and output == b""
        if followup == "run":
            assert b"DUPLICATE_MESSAGE" in errors
        assert b"SYNTHETIC" not in errors
    finally:
        stop(process)


def test_cancel_is_read_during_provider_wait_and_new_run_can_finish(tmp_path: Path) -> None:
    process = launch("blocked", tmp_path)
    try:
        ready(process)
        send(process, "run", payload())
        operation = receive(process)["payload"]
        send(
            process,
            "operation_result",
            {
                "operationId": operation["operationId"],
                "operation": "file.write",
                "outcome": "success",
                "result": {"path": "test.txt"},
            },
        )
        send(process, "cancel", {"reason": "user_cancelled"})
        response = receive(process)
        assert response["payload"]["error"]["code"] == "REQUEST_CANCELLED"
        assert response["payload"]["error"]["writeOutcome"] == "committed"
        send(process, "shutdown", {"reason": "application_exit"}, None)
        output, _ = process.communicate(timeout=10)
        assert output == b"" and process.returncode == 0
    finally:
        stop(process)
