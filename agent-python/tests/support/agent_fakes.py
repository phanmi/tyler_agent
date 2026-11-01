"""Deterministic provider/operation doubles; never use credentials or network transport."""

from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from copy import deepcopy

from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import (
    AssistantTurn,
    Provider,
    ToolCall,
    ToolDefinition,
    TranscriptEntry,
)


def payload() -> Message:
    return {
        "message": "current",
        "history": [],
        "provider": "openai",
        "model": "configured-model",
        "apiKey": "SYNTHETIC_FIXTURE_KEY",
        "maxToolRounds": 5,
        "timeoutMs": 300000,
        "operationTimeoutMs": 60000,
    }


def call(name: str = "getCurrentTime", raw: str = "{}", call_id: str = "call-1") -> ToolCall:
    return ToolCall(call_id, name, raw)


class FakeProvider:
    def __init__(self, responses: list[AssistantTurn | Exception]) -> None:
        self.responses = list(responses)
        self.requests: list[Message] = []
        self.snapshots: list[Message] = []
        self.closed = 0

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        self.requests.append(deepcopy(dict(model=model, transcript=transcript, tools=list(tools))))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response

    @asynccontextmanager
    async def open(self, value: Message) -> AsyncIterator[Provider]:
        self.snapshots.append(deepcopy(value))
        try:
            yield self
        finally:
            self.closed += 1
