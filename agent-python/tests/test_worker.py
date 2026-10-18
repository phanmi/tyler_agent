"""Real pipe smoke tests, optionally run against a relocated standalone Windows bundle."""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from queue import Queue
from threading import Thread
from typing import Any

import pytest
from conftest import ROOT, cases, sequence_message

from tyler_agent.resources import release_version

SESSION = "0123456789abcdef0123456789abcdef"


def envelope(kind: str, payload: dict[str, Any], request_id: str | None = None) -> bytes:
    return (
        json.dumps(
            {
                "protocolVersion": 1,
                "type": kind,
                "sessionId": SESSION,
                "requestId": request_id,
                "payload": payload,
            }
        )
        + "\n"
    ).encode()


@pytest.fixture
def worker_command(worker_executable: str | None, tmp_path: Path) -> list[str]:
    if worker_executable:
        original = Path(worker_executable)
        destination = tmp_path / "relocated worker"
        shutil.copytree(original.parent, destination)
        return [str(destination / original.name)]
    return [sys.executable, "-m", "tyler_agent"]


def start(command: list[str], cwd: Path, *, session: str = SESSION) -> subprocess.Popen[bytes]:
    # No installed interpreter, development modules, or credentials are discoverable by PATH.
    environment = {
        key: os.environ[key] for key in ("SystemRoot", "TEMP", "TMP") if key in os.environ
    }
    environment.update(PATH="", PYTHONNOUSERSITE="1", TYLER_AGENT_SESSION_ID=session)
    return subprocess.Popen(
        command,
        cwd=cwd,
        env=environment,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def ready(process: subprocess.Popen[bytes]) -> dict[str, Any]:
    assert process.stdout is not None
    stdout = process.stdout
    lines: Queue[bytes] = Queue()
    Thread(target=lambda: lines.put(stdout.readline()), daemon=True).start()
    line = lines.get(timeout=30)
    assert line.endswith(b"\n") and not line.endswith(b"\r\n")
    value: dict[str, Any] = json.loads(line)
    assert value == {
        "protocolVersion": 1,
        "type": "ready",
        "sessionId": SESSION,
        "requestId": None,
        "payload": {"workerVersion": release_version()},
    }
    return value


def stop(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is None:
        process.kill()
    process.communicate(timeout=10)


def test_ready_without_credentials_then_shutdown(worker_command: list[str], tmp_path: Path) -> None:
    process = start(worker_command, tmp_path)
    try:
        ready(process)
        output, errors = process.communicate(
            envelope("shutdown", {"reason": "application_exit"}), timeout=10
        )
        assert output == b""
        assert process.returncode == 0
        assert b"Worker ready" in errors and b"shutdown accepted" in errors
    finally:
        stop(process)


def test_run_is_explicitly_unavailable(worker_command: list[str], tmp_path: Path) -> None:
    process = start(worker_command, tmp_path)
    try:
        ready(process)
        run = envelope(
            "run",
            {
                "message": "secret-user-content",
                "history": [],
                "model": "test-model",
                "apiKey": "synthetic-secret",
                "maxToolRounds": 5,
                "timeoutMs": 300000,
                "operationTimeoutMs": 60000,
            },
            "req-1",
        )
        output, errors = process.communicate(
            run + envelope("shutdown", {"reason": "application_exit"}), timeout=10
        )
        failure = json.loads(output)
        assert failure["type"] == "failed" and failure["requestId"] == "req-1"
        assert failure["payload"]["error"]["code"] == "WORKER_UNAVAILABLE"
        assert failure["payload"]["error"]["writeOutcome"] == "none"
        assert process.returncode == 0
        assert b"synthetic-secret" not in errors and b"secret-user-content" not in errors
    finally:
        stop(process)


@pytest.mark.parametrize(
    "input_bytes,code", [(b"", 0), (b'{"apiKey":"secret"', 2), (b"not-json\n", 2)]
)
def test_eof_and_malformed_input(
    worker_command: list[str], tmp_path: Path, input_bytes: bytes, code: int
) -> None:
    process = start(worker_command, tmp_path)
    try:
        ready(process)
        output, errors = process.communicate(input_bytes, timeout=10)
        assert output == b"" and process.returncode == code
        assert b"secret" not in errors
    finally:
        stop(process)


def test_invalid_session_has_no_readiness(worker_command: list[str], tmp_path: Path) -> None:
    process = start(worker_command, tmp_path, session="invalid")
    try:
        output, errors = process.communicate(timeout=10)
        assert process.returncode == 2 and output == b""
        assert b"SESSION_MISMATCH" in errors
    finally:
        stop(process)


@pytest.mark.parametrize(
    "case",
    [
        case
        for case in cases("sequences")
        if case["id"].startswith("precedence_") and case["steps"][-1]["from"] == "java"
    ],
    ids=lambda item: item["id"],
)
def test_rejection_precedence_on_real_pipe(
    worker_command: list[str], tmp_path: Path, case: dict[str, Any]
) -> None:
    step = case["steps"][-1]
    message = sequence_message(step)
    process = start(worker_command, tmp_path)
    try:
        ready(process)
        output, errors = process.communicate(json.dumps(message).encode() + b"\n", timeout=10)
        assert output == b"" and process.returncode == 2
        assert errors.splitlines()[-1].startswith(
            (
                "ERROR Worker protocol failure: code=" + step["expected"]["reason"] + " detail="
            ).encode()
        )
        assert not errors.splitlines()[-1].endswith(b"detail=")
        assert b"SYNTHETIC_FIXTURE_KEY" not in errors
        assert b"Help me plan" not in errors
    finally:
        stop(process)


def test_release_version_comes_from_shared_file() -> None:
    assert f"-Drevision={release_version()}" in (ROOT / ".mvn/maven.config").read_text("utf-8")


@pytest.mark.parametrize("followup,exit_code", [("run", 2), ("cancel", 0)])
def test_request_identity_after_unavailable_reply(
    worker_command: list[str], tmp_path: Path, followup: str, exit_code: int
) -> None:
    process = start(worker_command, tmp_path)
    run = json.dumps(sequence_message({"ref": "run"})).encode() + b"\n"
    next_message = json.dumps(sequence_message({"ref": followup})).encode() + b"\n"
    try:
        ready(process)
        output, errors = process.communicate(run + next_message, timeout=10)
        assert process.returncode == exit_code
        replies = [json.loads(line) for line in output.splitlines()]
        assert len(replies) == 1
        assert replies[0]["payload"]["error"]["code"] == "WORKER_UNAVAILABLE"
        if followup == "run":
            assert b"DUPLICATE_MESSAGE" in errors
        assert b"SYNTHETIC_FIXTURE_KEY" not in errors
    finally:
        stop(process)
