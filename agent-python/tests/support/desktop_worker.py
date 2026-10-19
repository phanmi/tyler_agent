"""Packaged fault fixture, shipped only to smoke jobs, never in the release artifact."""

import asyncio
import json
import os
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import ModelResponse, Provider, ToolCall
from tyler_agent.worker import main


class BlockedProvider:
    async def respond(
        self, model: str, items: list[Message], tools: list[Message], previous_id: str | None
    ) -> ModelResponse:
        if previous_id is None:
            return ModelResponse(
                "first",
                (ToolCall("write", "writeFile", '{"path":"smoke.txt","content":"saved"}'),),
                "",
            )
        await asyncio.Event().wait()
        raise AssertionError("Blocked provider must be cancelled")


@asynccontextmanager
async def provider(value: Message) -> AsyncIterator[Provider]:
    yield BlockedProvider()


def run() -> int:
    mode = Path(sys.executable).with_name("scenario.txt").read_text(encoding="utf-8")
    if mode == "incompatible":
        print(
            json.dumps(
                dict(
                    protocolVersion=2,
                    type="ready",
                    sessionId=os.environ["TYLER_AGENT_SESSION_ID"],
                    requestId=None,
                    payload=dict(workerVersion="0.0.0"),
                )
            ),
            flush=True,
        )
        return 2
    if mode == "timeout":
        sys.stdin.buffer.read()
        return 0
    if mode == "startup_failure":
        return 2
    return main(provider)


if __name__ == "__main__":
    raise SystemExit(run())
