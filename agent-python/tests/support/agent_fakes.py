"""Deterministic provider/operation doubles; never use credentials or network transport."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from copy import deepcopy

from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import ModelResponse, Provider, ToolCall


def payload() -> Message:
    return {
        "message": "current",
        "history": [],
        "model": "configured-model",
        "apiKey": "SYNTHETIC_FIXTURE_KEY",
        "maxToolRounds": 5,
        "timeoutMs": 300000,
        "operationTimeoutMs": 60000,
    }


def call(name: str = "getCurrentTime", raw: str = "{}", call_id: str = "call-1") -> ToolCall:
    return ToolCall(call_id, name, raw)


class FakeProvider:
    def __init__(self, responses: list[ModelResponse | Exception]) -> None:
        self.responses = list(responses)
        self.requests: list[Message] = []
        self.snapshots: list[Message] = []
        self.closed = 0

    async def respond(
        self, model: str, items: list[Message], tools: list[Message], previous_id: str | None
    ) -> ModelResponse:
        self.requests.append(
            deepcopy(dict(model=model, input=items, tools=tools, previous=previous_id))
        )
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
