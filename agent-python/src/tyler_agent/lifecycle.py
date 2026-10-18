"""Exit with the owning Java process, independently of a blocked provider or pipe handler."""

import ctypes
import logging
import os
import sys
from collections.abc import Callable
from threading import Event, Thread

log = logging.getLogger(__name__)

if sys.platform == "win32":
    from ctypes import wintypes

    def _parent_probe(pid: int) -> tuple[Callable[[], bool], Callable[[], None]]:
        """Hold a process handle so PID reuse cannot make a dead Java owner appear alive."""
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.CloseHandle.restype = wintypes.BOOL
        handle = kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE only.
        if not handle:
            raise OSError("Cannot monitor worker parent")

        def alive() -> bool:
            return int(kernel.WaitForSingleObject(handle, 0)) == 258  # WAIT_TIMEOUT

        def close() -> None:
            kernel.CloseHandle(handle)

        return alive, close

else:

    def _parent_probe(pid: int) -> tuple[Callable[[], bool], Callable[[], None]]:
        def alive() -> bool:
            # A direct child gets reparented on owner exit, even if the old PID is reused.
            return os.getppid() == pid

        return alive, lambda: None


class ParentMonitor:
    """Own a small watchdog; normal cleanup is idempotent and never waits on model work."""

    def __init__(self, parent_pid: int, exit_process: Callable[[int], None] = os._exit) -> None:
        if parent_pid <= 0 or parent_pid > 0xFFFFFFFF:
            raise ValueError("Invalid worker parent identifier")
        self._alive, self._release = _parent_probe(parent_pid)
        self._exit = exit_process
        self._stop = Event()
        self._thread = Thread(target=self._watch, name="agent-parent-monitor", daemon=True)
        self._thread.start()

    @classmethod
    def from_environment(cls) -> "ParentMonitor":
        value = os.environ.get("TYLER_AGENT_PARENT_PID")
        return cls(int(value) if value is not None else os.getppid())

    def _watch(self) -> None:
        while not self._stop.wait(0.1):
            if not self._alive():
                log.info("Worker owner exited; terminating worker")
                self._exit(0)
                return

    def close(self) -> None:
        if self._stop.is_set():
            return
        self._stop.set()
        self._thread.join(timeout=1)
        self._release()
