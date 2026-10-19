"""Single-event-loop owner of active runs, callbacks, cancellation, and safe terminal messages."""

import asyncio
import logging
from collections.abc import Callable
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass, field

from tyler_agent.agent import run_agent
from tyler_agent.failures import AgentFailure, OperationFailure
from tyler_agent.protocol.protocol import Message, ProtocolError
from tyler_agent.provider import Provider, open_provider
from tyler_agent.session import WRITES
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.discovery import discover_tools
from tyler_agent.tools.registry import ToolCatalog

log = logging.getLogger(__name__)
MAX_REQUESTS = 10_000
type Sender = Callable[[str, str | None, Message], None]
type ProviderFactory = Callable[[Message], AbstractAsyncContextManager[Provider]]


@dataclass
class Run:
    request_id: str
    operation_timeout: float
    task: asyncio.Task[None] | None = None
    pending: tuple[str, str, asyncio.Future[Message]] | None = None
    resolved: set[str] = field(default_factory=set)
    operation_count: int = 0
    committed: bool = False
    uncertain: bool = False
    cancel_code: str = "REQUEST_CANCELLED"

    def write_outcome(self) -> str:
        return "unknown" if self.uncertain else "committed" if self.committed else "none"


class WorkerRuntime:
    """All methods execute on one loop; the pipe reader schedules only short accept calls."""

    def __init__(self, send: Sender, provider_factory: ProviderFactory = open_provider) -> None:
        self._send = send
        self._provider_factory = provider_factory
        # Load bundled adapters/resources before readiness, without creating a provider.
        self._tools = ToolCatalog(discover_tools())
        self._requests: set[str] = set()
        self._active: Run | None = None
        self._closing = False

    async def accept(self, message: Message) -> bool:
        """Receive already validated Java messages; stale results never resume a finished run."""
        kind = message["type"]
        if kind == "shutdown":
            await self.close()
            return False
        if kind == "run":
            self._begin(message)
            return True
        run = self._active
        if run is None or message["requestId"] != run.request_id:
            if message["requestId"] in self._requests:
                return True
            raise ProtocolError("REQUEST_NOT_ACTIVE", "Message does not belong to a known request")
        if kind == "cancel":
            self._cancel(run, message["payload"]["reason"])
        elif kind == "operation_result":
            self._result(run, message["payload"])
        else:
            raise ProtocolError("INVALID_SEQUENCE", "Message cannot be accepted during a run")
        return True

    def _begin(self, message: Message) -> None:
        request_id = message["requestId"]
        if request_id in self._requests:
            raise ProtocolError("DUPLICATE_MESSAGE", "Request identifier has already been used")
        if self._closing or self._active is not None or len(self._requests) >= MAX_REQUESTS:
            raise ProtocolError("INVALID_SEQUENCE", "Worker cannot start another run")
        self._requests.add(request_id)
        run = Run(request_id, message["payload"]["operationTimeoutMs"] / 1000)
        self._active = run
        run.task = asyncio.create_task(self._execute(run, message["payload"]))
        run.task.add_done_callback(lambda task: self._task_finished(run, task))

    def _task_finished(self, run: Run, task: asyncio.Task[None]) -> None:
        # Cancellation can arrive before the coroutine enters its try/finally block.
        if task.cancelled():
            category = "timeout" if run.cancel_code == "DEADLINE_EXCEEDED" else "cancelled"
            self._fail(run, AgentFailure(category, run.cancel_code))
            if self._active is run:
                self._active = None
        elif task.exception() is not None:
            # Observe pipe-write failures without logging payload-bearing exception text.
            log.error("Agent terminal delivery failed")

    async def _execute(self, run: Run, payload: Message) -> None:
        try:
            async with asyncio.timeout(payload["timeoutMs"] / 1000):
                async with self._provider_factory(payload) as provider:

                    async def operation(name: str, value: Message) -> Message:
                        return await self._operation(run, name, value)

                    reply = await run_agent(payload, provider, ToolAdapters(operation, self._tools))
            if not self._closing:
                self._send("completed", run.request_id, {"reply": reply})
                log.info("Agent run completed")
        except asyncio.CancelledError:
            category = "timeout" if run.cancel_code == "DEADLINE_EXCEEDED" else "cancelled"
            self._fail(run, AgentFailure(category, run.cancel_code))
        except TimeoutError:
            self._fail(run, AgentFailure("timeout", "DEADLINE_EXCEEDED"))
        except AgentFailure as error:
            self._fail(run, error)
        except ProtocolError as error:
            self._fail(run, AgentFailure("protocol", error.code))
        except Exception:
            # Top-level containment: provider/library bugs must not leak request data or
            # silently strand a run. Its Java owner receives a classified terminal failure.
            self._fail(run, AgentFailure("worker", "INTERNAL_FAILURE"))
        finally:
            if run.pending is not None:
                run.pending[2].cancel()
                run.pending = None
            self._active = None

    def _fail(self, run: Run, error: AgentFailure) -> None:
        log.info("Agent run failed: code=%s writeOutcome=%s", error.code, run.write_outcome())
        if not self._closing:
            self._send("failed", run.request_id, {"error": error.payload(run.write_outcome())})

    async def _operation(self, run: Run, name: str, value: Message) -> Message:
        await asyncio.sleep(0)
        if self._closing or run.task is None or run.task.cancelling():
            raise asyncio.CancelledError
        run.operation_count += 1
        operation_id = f"op-{run.operation_count}"
        future: asyncio.Future[Message] = asyncio.get_running_loop().create_future()
        run.pending = (operation_id, name, future)
        # A sent write may commit even if its result is lost. Never infer rollback from timeout.
        run.uncertain = name in WRITES
        self._send(
            "operation_request",
            run.request_id,
            {"operationId": operation_id, "operation": name, "input": value},
        )
        async with asyncio.timeout(run.operation_timeout):
            result = await future
        if result["outcome"] == "failure":
            raise OperationFailure(result["error"])
        return dict(result["result"])

    def _result(self, run: Run, payload: Message) -> None:
        operation_id, name = payload["operationId"], payload["operation"]
        if operation_id in run.resolved:
            raise ProtocolError("DUPLICATE_MESSAGE", "Operation result has already been received")
        if run.pending is None or run.pending[:2] != (operation_id, name):
            raise ProtocolError(
                "OPERATION_NOT_PENDING", "Result does not match the pending operation"
            )
        future = run.pending[2]
        run.resolved.add(operation_id)
        run.pending = None
        if payload["outcome"] == "success":
            run.committed |= name in WRITES
            run.uncertain = False
        else:
            outcome = payload["error"]["writeOutcome"]
            run.committed |= outcome == "committed"
            run.uncertain = outcome == "unknown"
        if not future.done():
            future.set_result(payload)

    def _cancel(self, run: Run, reason: str) -> None:
        if run.task is not None and not run.task.cancelling():
            run.cancel_code = (
                "DEADLINE_EXCEEDED" if reason == "deadline_exceeded" else "REQUEST_CANCELLED"
            )
            run.task.cancel()

    async def close(self) -> None:
        """Cancel and await owned work before the loop exits; repeated close is safe."""
        self._closing = True
        run = self._active
        if run is not None and run.task is not None:
            self._cancel(run, "shutdown")
            await asyncio.gather(run.task, return_exceptions=True)
