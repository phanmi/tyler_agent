"""Exit with the owning Java process, independently of a blocked provider or pipe handler."""

import ctypes
import logging
import os
import sys
from collections.abc import Callable
from threading import Event, Thread

log = logging.getLogger(__name__)

PARENT_PID_ENV = "TYLER_AGENT_PARENT_PID"
MAX_PROCESS_ID = 0xFFFFFFFF
PARENT_POLL_INTERVAL_SECONDS = 0.1
PROCESS_SYNCHRONIZE_ACCESS = 0x00100000
WAIT_TIMEOUT = 258
NONBLOCKING_WAIT_MS = 0

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
        handle = kernel.OpenProcess(PROCESS_SYNCHRONIZE_ACCESS, False, pid)
        if not handle:
            raise OSError("Cannot monitor worker parent")

        def alive() -> bool:
            return int(kernel.WaitForSingleObject(handle, NONBLOCKING_WAIT_MS)) == WAIT_TIMEOUT

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
        if parent_pid <= 0 or parent_pid > MAX_PROCESS_ID:
            raise ValueError("Invalid worker parent identifier")
        self._alive, self._release = _parent_probe(parent_pid)
        self._exit = exit_process
        self._stop = Event()
        self._thread = Thread(target=self._watch, name="agent-parent-monitor", daemon=True)
        try:
            self._thread.start()
        except RuntimeError:
            self._release()
            raise

    @classmethod
    def from_environment(cls) -> "ParentMonitor":
        value = os.environ.get(PARENT_PID_ENV)
        return cls(int(value) if value is not None else os.getppid())

    def _watch(self) -> None:
        try:
            while not self._stop.wait(PARENT_POLL_INTERVAL_SECONDS):
                if not self._alive():
                    log.info("Worker owner exited; terminating worker")
                    self._exit(0)
                    return
        finally:
            # The probe thread owns the handle until its final access, even during close.
            self._release()

    def close(self) -> None:
        self._stop.set()
        # Production probes are nonblocking OS calls. The event interrupts the polling wait;
        # join confirms termination instead of releasing a handle a live probe could still use.
        self._thread.join()
