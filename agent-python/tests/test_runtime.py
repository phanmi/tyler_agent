"""Controlled request/operation ordering, cancellation, deadlines, and safe completion."""

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import pytest
from support.agent_fakes import FakeProvider, call, payload

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message, ProtocolError
from tyler_agent.provider import ModelResponse, Provider
from tyler_agent.runtime import WorkerRuntime


def message(kind: str, value: Message, request: str = "req-1") -> Message:
    return {"type": kind, "requestId": request, "payload": value}


class Harness:
    def __init__(self, provider: FakeProvider) -> None:
        self.messages: asyncio.Queue[Message] = asyncio.Queue()
        self.runtime = WorkerRuntime(self.send, provider.open)

    def send(self, kind: str, request: str | None, value: Message) -> None:
        self.messages.put_nowait({"type": kind, "requestId": request, "payload": value})

    async def next(self) -> Message:
        return await asyncio.wait_for(self.messages.get(), 3)

    async def result(
        self, request: Message, result: Message | None = None, error: Message | None = None
    ) -> None:
        operation = request["payload"]
        value = {
            "operationId": operation["operationId"],
            "operation": operation["operation"],
            "outcome": "failure" if error else "success",
        }
        value["error" if error else "result"] = error if error else result
        await self.runtime.accept(message("operation_result", value, request["requestId"]))


def test_completed_and_each_run_uses_new_key_history_and_client_scope() -> None:
    async def scenario() -> None:
        provider = FakeProvider([ModelResponse("r1", (), "one"), ModelResponse("r2", (), "two")])
        h = Harness(provider)
        try:
            for i in (1, 2):
                data = payload() | {
                    "apiKey": f"synthetic-key-{i}",
                    "history": [{"role": "user", "content": str(i)}],
                }
                await h.runtime.accept(message("run", data, f"req-{i}"))
                result = await h.next()
                assert result["type"] == "completed" and result["requestId"] == f"req-{i}"
            assert provider.closed == 2
            assert [p["apiKey"] for p in provider.snapshots] == [
                "synthetic-key-1",
                "synthetic-key-2",
            ]
            assert [r["input"][0]["content"] for r in provider.requests] == ["1", "2"]
        finally:
            await h.runtime.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "category,code",
    [
        ("validation", "INVALID_ARGUMENT"),
        ("storage", "SQL_WRITE"),
        ("storage", "FILE_WRITE"),
        ("storage", "SQL_READ"),
    ],
)
def test_java_failure_forwarded_without_later_tools(category: str, code: str) -> None:
    async def scenario() -> None:
        provider = FakeProvider([ModelResponse("r", (call(), call()), "")])
        h = Harness(provider)
        try:
            await h.runtime.accept(message("run", payload()))
            operation = await h.next()
            error = dict(
                category=category, code=code, message="Java-safe detail", writeOutcome="unknown"
            )
            await h.result(operation, error=error)
            terminal = await h.next()
            assert terminal["type"] == "failed" and terminal["payload"]["error"] == error
            assert h.messages.empty() and provider.closed == 1
        finally:
            await h.runtime.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "failure",
    [AgentFailure("provider", "PROVIDER_FAILURE"), RuntimeError("secret-key prompt file")],
)
def test_failure_after_write_reports_commit_without_replay(
    failure: Exception, caplog: pytest.LogCaptureFixture
) -> None:
    async def scenario() -> None:
        provider = FakeProvider(
            [
                ModelResponse("r", (call("writeFile", '{"path":"x","content":"secret"}'),), ""),
                failure,
            ]
        )
        h = Harness(provider)
        try:
            await h.runtime.accept(message("run", payload()))
            await h.result(await h.next(), {"path": "x"})
            terminal = await h.next()
            assert terminal["type"] == "failed"
            assert terminal["payload"]["error"]["writeOutcome"] == "committed"
            assert "secret" not in str(terminal) and "secret" not in caplog.text
            assert len(provider.requests) == 2 and provider.closed == 1
        finally:
            await h.runtime.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "reason,code",
    [("user_cancelled", "REQUEST_CANCELLED"), ("deadline_exceeded", "DEADLINE_EXCEEDED")],
)
def test_cancel_pending_write_keeps_unknown_and_ignores_late_result(reason: str, code: str) -> None:
    async def scenario() -> None:
        provider = FakeProvider(
            [
                ModelResponse("r", (call("writeFile", '{"path":"x","content":"y"}'), call()), ""),
                ModelResponse("next", (), "next reply"),
            ]
        )
        h = Harness(provider)
        try:
            await h.runtime.accept(message("run", payload()))
            operation = await h.next()
            await h.runtime.accept(message("cancel", {"reason": reason}))
            await h.runtime.accept(message("cancel", {"reason": reason}))
            terminal = await h.next()
            assert terminal["payload"]["error"]["code"] == code
            assert terminal["payload"]["error"]["writeOutcome"] == "unknown"
            await h.runtime.accept(message("run", payload(), "req-2"))
            await h.result(operation, {"path": "x"})
            result = await h.next()
            assert result["type"] == "completed" and result["requestId"] == "req-2"
            assert len(provider.requests) == 2 and h.messages.empty()
        finally:
            await h.runtime.close()

    asyncio.run(scenario())


def test_wrong_or_duplicate_operation_result_is_rejected() -> None:
    async def scenario() -> None:
        h = Harness(
            FakeProvider([ModelResponse("r", (call(),), ""), ModelResponse("r2", (), "done")])
        )
        try:
            await h.runtime.accept(message("run", payload()))
            request = await h.next()
            with pytest.raises(ProtocolError, match="OPERATION_NOT_PENDING"):
                await h.runtime.accept(
                    message(
                        "operation_result", {"operationId": "wrong", "operation": "time.current"}
                    )
                )
            await h.result(request, {"text": "time"})
            with pytest.raises(ProtocolError, match="DUPLICATE_MESSAGE"):
                await h.result(request, {"text": "time"})
            assert (await h.next())["type"] == "completed"
        finally:
            await h.runtime.close()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    "request_id,code", [("req-1", "DUPLICATE_MESSAGE"), ("req-2", "INVALID_SEQUENCE")]
)
def test_second_run_rejected_while_active(request_id: str, code: str) -> None:
    async def scenario() -> None:
        h = Harness(FakeProvider([ModelResponse("r", (call(),), "")]))
        try:
            await h.runtime.accept(message("run", payload()))
            await h.next()
            with pytest.raises(ProtocolError, match=code):
                await h.runtime.accept(message("run", payload(), request_id))
        finally:
            await h.runtime.close()

    asyncio.run(scenario())


class BlockedProvider:
    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.closed = asyncio.Event()

    async def respond(
        self, model: str, items: list[Message], tools: list[Message], previous_id: str | None
    ) -> ModelResponse:
        self.started.set()
        await asyncio.Event().wait()
        raise AssertionError("Unreachable")

    @asynccontextmanager
    async def open(self, value: Message) -> AsyncIterator[Provider]:
        try:
            yield self
        finally:
            self.closed.set()


@pytest.mark.parametrize("event", ["cancel", "shutdown", "eof", "deadline"])
def test_provider_wait_is_cancelled_and_client_closed(event: str) -> None:
    async def scenario() -> None:
        provider = BlockedProvider()
        output: asyncio.Queue[Message] = asyncio.Queue()
        runtime = WorkerRuntime(
            lambda kind, request, value: output.put_nowait({"type": kind, "payload": value}),
            provider.open,
        )
        try:
            data = payload()
            if event == "deadline":
                data["timeoutMs"] = 20  # Test the internal timer without a real wall-clock second.
            await runtime.accept(message("run", data))
            await asyncio.wait_for(provider.started.wait(), 1)
            if event == "eof":
                await runtime.close()
            elif event != "deadline":
                await runtime.accept(message(event, {"reason": "user_cancelled"}))
            if event in {"cancel", "deadline"}:
                terminal = await asyncio.wait_for(output.get(), 1)
                assert terminal["payload"]["error"]["code"] == (
                    "DEADLINE_EXCEEDED" if event == "deadline" else "REQUEST_CANCELLED"
                )
            assert provider.closed.is_set()
            assert output.empty()
        finally:
            await runtime.close()

    asyncio.run(scenario())


def test_operation_timeout_leaves_write_uncertain_and_does_not_continue() -> None:
    async def scenario() -> None:
        h = Harness(
            FakeProvider(
                [ModelResponse("r", (call("writeFile", '{"path":"x","content":"y"}'), call()), "")]
            )
        )
        try:
            await h.runtime.accept(message("run", payload() | {"operationTimeoutMs": 20}))
            assert (await h.next())["type"] == "operation_request"
            error = (await h.next())["payload"]["error"]
            assert error["code"] == "DEADLINE_EXCEEDED" and error["writeOutcome"] == "unknown"
        finally:
            await h.runtime.close()

    asyncio.run(scenario())


def test_cancel_before_coroutine_starts_still_finishes_once() -> None:
    async def scenario() -> None:
        h = Harness(FakeProvider([]))
        try:
            await h.runtime.accept(message("run", payload()))
            await h.runtime.accept(message("cancel", {"reason": "user_cancelled"}))
            assert (await h.next())["payload"]["error"]["code"] == "REQUEST_CANCELLED"
            assert h.messages.empty()
        finally:
            await h.runtime.close()

    asyncio.run(scenario())
