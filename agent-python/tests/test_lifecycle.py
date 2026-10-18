"""Parent monitoring must exit independently of blocked application work and release handles."""

import os
from threading import Event

import pytest

from tyler_agent import lifecycle


def test_parent_loss_exits_and_cleanup_is_idempotent(monkeypatch: pytest.MonkeyPatch) -> None:
    exited = Event()
    releases: list[bool] = []
    monkeypatch.setattr(
        lifecycle, "_parent_probe", lambda pid: (lambda: False, lambda: releases.append(True))
    )
    monitor = lifecycle.ParentMonitor(123, lambda code: exited.set())
    try:
        assert exited.wait(3)
    finally:
        monitor.close()
        monitor.close()
    assert releases == [True]


def test_live_parent_monitor_closes_normally() -> None:
    # The test process has a real live parent; no mocked native handle is needed.
    monitor = lifecycle.ParentMonitor(os.getppid())
    monitor.close()
    monitor.close()


@pytest.mark.parametrize("value", ["0", "-1", "not-a-pid", "4294967296"])
def test_invalid_parent_identity_is_rejected(monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv("TYLER_AGENT_PARENT_PID", value)
    with pytest.raises(ValueError):
        lifecycle.ParentMonitor.from_environment()
