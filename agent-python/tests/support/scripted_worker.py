"""Launch the real pipe/runtime code with a fake provider, exclusively from tests."""

import asyncio
import sys
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import (
    AssistantTurn,
    Provider,
    ToolCall,
    ToolDefinition,
    TranscriptEntry,
)
from tyler_agent.worker import main


class ScriptedProvider:
    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.count = 0

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        self.count += 1
        if self.count == 1 and self.mode != "reply":
            return AssistantTurn(
                (ToolCall("original", "writeFile", '{"path":"test.txt","content":"text"}'),),
                "",
            )
        if self.mode == "write_then_read" and self.count == 2:
            return AssistantTurn((ToolCall("read", "readFile", '{"path":"missing.txt"}'),), "")
        if self.mode == "failure":
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        if self.mode == "blocked":
            await asyncio.Event().wait()
        return AssistantTurn((), "finished")


@asynccontextmanager
async def provider(value: Message) -> AsyncIterator[Provider]:
    yield ScriptedProvider(sys.argv[1])


if __name__ == "__main__":
    raise SystemExit(main(provider))
