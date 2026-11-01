"""Production worker with a deterministic model for the Java HTTP integration suite.

Only this test entry point interprets scenario names. Application operations, transport,
tool parsing, orchestration, and lifecycle all use production code.
"""

import json
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import (
    AssistantTurn,
    Provider,
    ToolCall,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    responses_input,
)
from tyler_agent.worker import main


def food_call(**changes: object) -> ToolCall:
    info = dict(foodName="Oats", amount=100, unit="g", calories=389, date="2026-10-19")
    info.update(changes)
    return tool(
        "recordFood",
        dict(genericInfo=info, macroNutrients=dict(protein=17, carbs=66, fat=7, fiber=10)),
    )


def tool(name: str, arguments: Message) -> ToolCall:
    return ToolCall(name, name, json.dumps(arguments))


def calls_for(scenario: str) -> tuple[ToolCall, ...]:
    """Return model-authored inputs; Java services still decide domain validity."""
    cases = {
        "food": (food_call(),),
        "two_food": (food_call(), food_call()),
        "before_write": (tool("getCurrentTime", {}), food_call()),
        "negative_food": (food_call(amount=-1),),
        "invalid_date": (food_call(date="2026-02-30"),),
        "missing_food": (tool("recordFood", {}),),
        "plan": (
            tool(
                "generateWorkoutPlan",
                dict(startDate="2026-10-19", goal="general_fitness", equipment="bodyweight"),
            ),
        ),
        "missing_plan": (tool("generateWorkoutPlan", {}),),
        "file_failure": (tool("readFile", dict(path="missing.txt")),),
        "food_then_failure": (food_call(),),
        "reply": (),
    }
    # Distinct call IDs exercise the production loop's correlation across equal tool calls.
    return tuple(
        ToolCall(str(index), call.name, call.arguments)
        for index, call in enumerate(cases[scenario])
    )


class PersistenceProvider:
    """Script only model responses, retaining real Java callbacks and Python tool execution."""

    def __init__(self, value: Message) -> None:
        self.scenario: str = value["message"]
        self.history: list[Message] = value["history"]
        self.history_size = len(value["history"])
        # Reported back so Java can check which provider the run selected.
        self.provider: str = value["provider"]

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        first = not any(isinstance(entry, ToolResult) for entry in transcript)
        if first:
            assert responses_input(transcript) == self.history + [
                {"role": "user", "content": self.scenario}
            ]
        if self.scenario == "auth_failure":
            raise AgentFailure("authentication", "PROVIDER_AUTH")
        if self.scenario == "provider_failure" or (
            self.scenario == "food_then_failure" and not first
        ):
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        if first:
            calls = calls_for(self.scenario)
            if calls:
                return AssistantTurn(calls, "")
        outputs = [entry.output for entry in transcript if isinstance(entry, ToolResult)]
        reply = dict(
            historySize=self.history_size, outputs=outputs, provider=self.provider, model=model
        )
        return AssistantTurn((), json.dumps(reply))


@asynccontextmanager
async def provider(value: Message) -> AsyncIterator[Provider]:
    yield PersistenceProvider(value)


if __name__ == "__main__":
    raise SystemExit(main(provider))
