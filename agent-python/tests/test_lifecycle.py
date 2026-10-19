"""Parent monitoring must exit independently of blocked application work and release handles."""

import os
from threading import Event, Thread

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


def test_close_waits_for_probe_exit_before_releasing_handle(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    entered, unblock, released, closing, closed = (Event() for _ in range(5))

    def alive() -> bool:
        entered.set()
        assert unblock.wait(5)
        assert not released.is_set()
        return True

    monkeypatch.setattr(lifecycle, "_parent_probe", lambda pid: (alive, released.set))
    monitor = lifecycle.ParentMonitor(123)

    def close() -> None:
        closing.set()
        monitor.close()
        closed.set()

    closer = Thread(target=close)
    try:
        assert entered.wait(3)
        closer.start()
        assert closing.wait(3)
        # The previous one-second join returned while this probe still owned the handle.
        assert not closed.wait(1.1)
        assert not released.is_set()
    finally:
        unblock.set()
        if closer.ident is not None:
            closer.join(timeout=3)
        monitor.close()
    assert not closer.is_alive()
    assert closed.is_set()
    assert released.is_set()


def test_thread_start_failure_releases_handle(monkeypatch: pytest.MonkeyPatch) -> None:
    releases: list[bool] = []
    monkeypatch.setattr(
        lifecycle, "_parent_probe", lambda pid: (lambda: True, lambda: releases.append(True))
    )

    def fail_start(self: Thread) -> None:
        raise RuntimeError("Cannot start watchdog")

    monkeypatch.setattr(Thread, "start", fail_start)
    with pytest.raises(RuntimeError, match="Cannot start watchdog"):
        lifecycle.ParentMonitor(123)
    assert releases == [True]


@pytest.mark.parametrize("value", ["0", "-1", "not-a-pid", "4294967296"])
def test_invalid_parent_identity_is_rejected(monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv("TYLER_AGENT_PARENT_PID", value)
    with pytest.raises(ValueError):
        lifecycle.ParentMonitor.from_environment()
