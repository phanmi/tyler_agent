"""Packaged fault fixture, shipped only to smoke jobs, never in the release artifact."""

import asyncio
import json
import os
import sys
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from pathlib import Path

from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import (
    AssistantTurn,
    Provider,
    ToolCall,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
)
from tyler_agent.worker import main


class BlockedProvider:
    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        if not any(isinstance(entry, ToolResult) for entry in transcript):
            return AssistantTurn(
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
                    protocolVersion=1,  # Retired version.
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
