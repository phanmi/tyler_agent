"""Shared contract loading and optional packaged-worker selection."""

import json
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "contracts/agent/v1/fixtures"


def cases(name: str) -> list[dict[str, Any]]:
    value: list[dict[str, Any]] = json.loads((FIXTURES / f"{name}.json").read_text("utf-8"))[
        "cases"
    ]
    return value


def pytest_addoption(parser: pytest.Parser) -> None:
    parser.addoption("--worker-executable", help="Also smoke-test this standalone Windows bundle")


def pytest_generate_tests(metafunc: pytest.Metafunc) -> None:
    if "worker_executable" in metafunc.fixturenames:
        executable = metafunc.config.getoption("--worker-executable")
        values = [None, str(Path(executable).resolve())] if executable else [None]
        metafunc.parametrize("worker_executable", values)
