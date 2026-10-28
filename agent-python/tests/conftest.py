"""Shared contract loading and optional packaged-worker selection."""

import base64
import copy
import json
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts/agent/v2/fixtures"


def cases(name: str) -> list[dict[str, Any]]:
    value: list[dict[str, Any]] = json.loads((FIXTURES / f"{name}.json").read_text("utf-8"))[
        "cases"
    ]
    return value


def sequence_message(step: dict[str, Any]) -> dict[str, Any]:
    """Apply shared fixture replacements without running the outbound validation path."""
    message: dict[str, Any] = copy.deepcopy(
        next(case["message"] for case in cases("messages") if case["id"] == step["ref"])
    )
    for pointer, value in step.get("set", {}).items():
        keys = [key.replace("~1", "/").replace("~0", "~") for key in pointer[1:].split("/")]
        target = message
        for key in keys[:-1]:
            target = target[key]
        assert keys[-1] in target
        target[keys[-1]] = value
    return message


def frame_chunks(case: dict[str, Any]) -> list[bytes]:
    """Build shared byte fixtures and generated size boundaries independently of the codec."""
    if "generator" in case:
        generator = case["generator"]
        message = copy.deepcopy(generator["message"])
        base_size = len(json.dumps(message, separators=(",", ":")).encode())
        message["payload"]["reply"] = "x" * (generator["frameBytes"] - base_size)
        frame = json.dumps(message, separators=(",", ":")).encode()
        assert len(frame) == generator["frameBytes"]
        return [frame, b"\n"]
    return [base64.b64decode(value, validate=True) for value in case["chunksBase64"]]


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption("--worker-executable", help="Also smoke-test this standalone Windows bundle")


def pytest_generate_tests(metafunc: pytest.Metafunc) -> None:
    if "worker_executable" in metafunc.fixturenames:
        executable = metafunc.config.getoption("--worker-executable")
        values = [None, str(Path(executable).resolve())] if executable else [None]
        metafunc.parametrize("worker_executable", values)
