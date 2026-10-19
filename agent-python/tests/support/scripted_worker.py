"""Launch the real pipe/runtime code with a fake provider, exclusively from tests."""

import asyncio
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import ModelResponse, Provider, ToolCall
from tyler_agent.worker import main


class ScriptedProvider:
    def __init__(self, mode: str) -> None:
        self.mode = mode
        self.count = 0

    async def respond(
        self, model: str, items: list[Message], tools: list[Message], previous_id: str | None
    ) -> ModelResponse:
        self.count += 1
        if self.count == 1 and self.mode != "reply":
            return ModelResponse(
                "r1",
                (ToolCall("original", "writeFile", '{"path":"test.txt","content":"text"}'),),
                "",
            )
        if self.mode == "failure":
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        if self.mode == "blocked":
            await asyncio.Event().wait()
        return ModelResponse("r2", (), "finished")


@asynccontextmanager
async def provider(value: Message) -> AsyncIterator[Provider]:
    yield ScriptedProvider(sys.argv[1])


if __name__ == "__main__":
    raise SystemExit(main(provider))
