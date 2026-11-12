"""Scripted chats for the eval harness tests: real agent, tools and scoring, no provider."""

import json
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from dataclasses import replace
from decimal import Decimal
from itertools import count
from typing import Any

from evals.cases import Case, Expected
from evals.runner import Chat, ProviderFactory, Session, Target
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.verification import VERDICT_TOOL
from tyler_agent.tools.parsing import GENERIC_INFO_FIELDS, MACRO_FIELDS
from tyler_agent.transcript import (
    AssistantTurn,
    Provider,
    ToolCall,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    Usage,
)

KEY = "SYNTHETIC_FIXTURE_KEY"
NOW = "2026-11-12 19:30:00 EST"
TODAY = "2026-11-12"
TARGET = Target("openai", "configured-model")
# Every scripted reply reports this, so totals are a multiple of it.
USAGE = Usage(100, 10, 40, 5)


BASE_EXPECTED = Expected(
    names=("chicken breast",),
    amount=Decimal(200),
    units=("g", "gram", "grams"),
    date=TODAY,
    nutrition={
        "calories": Decimal(300),
        "protein": Decimal(60),
        "carbs": Decimal(0),
        "fat": Decimal(6),
        "fiber": Decimal(0),
    },
    stated=frozenset(),
    source="Synthetic reference for tests.",
    tolerance=None,
)


def expected(**changes: Any) -> Expected:
    """200 g of chicken breast today, with the given fields replaced."""
    return replace(BASE_EXPECTED, **changes)


def case(*expects: Expected, case_id: str = "chicken", history: tuple[Message, ...] = ()) -> Case:
    return Case(case_id, "test", NOW, history, "I ate 200 g of grilled chicken breast.", expects)


def chat(verify: bool, repeat: int = 1, subject: Case | None = None) -> Chat:
    return Chat(subject or case(expected()), TARGET, verify, repeat)


SAVED_FIELDS: dict[str, object] = dict(
    foodName="Grilled chicken breast",
    amount="200",
    unit="g",
    calories="302",
    date=TODAY,
    protein="61",
    carbs="0",
    fat="6.3",
    fiber="0",
)


def record(**changes: object) -> Message:
    """A saved record as food.insert receives it: numbers are decimal text."""
    fields = SAVED_FIELDS | changes
    return {
        "genericInfo": {name: fields[name] for name in GENERIC_INFO_FIELDS},
        "macroNutrients": {name: fields[name] for name in MACRO_FIELDS},
    }


def food_call(call_id: str = "food", **changes: object) -> ToolCall:
    """A recordFood call as a model writes it: numbers are JSON numbers."""
    info: dict[str, object] = dict(
        foodName="Grilled chicken breast", amount=200, unit="g", calories=302, date=TODAY
    )
    info.update(changes)
    macros = dict(protein=61, carbs=0, fat=6.3, fiber=0)
    return ToolCall(
        call_id, "recordFood", json.dumps({"genericInfo": info, "macroNutrients": macros})
    )


def verdict(valid: bool, reason: str = "Checked.") -> ToolCall:
    return ToolCall("verdict", VERDICT_TOOL.name, json.dumps({"valid": valid, "reason": reason}))


def turn(*calls: ToolCall, text: str = "", usage: Usage | None = USAGE) -> AssistantTurn:
    return AssistantTurn(tuple(calls), text, usage=usage)


def session(opener: ProviderFactory) -> Session:
    """A session whose clock advances one second per reading, so durations are exact."""
    ticks = count()
    return Session({TARGET.provider: KEY}, opener, lambda: float(next(ticks)))


class RuleProvider:
    """Answers like a well-behaved model for any number of chats: record the food once,
    accept every verification, then reply. `opened` counts the chats that reached it."""

    def __init__(self) -> None:
        self.opened = 0

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        if [tool.name for tool in tools] == [VERDICT_TOOL.name]:
            return turn(verdict(True))
        if any(isinstance(entry, ToolResult) for entry in transcript):
            return turn(text="Recorded.")
        return turn(food_call())

    @asynccontextmanager
    async def open(self, value: Message) -> AsyncIterator[Provider]:
        self.opened += 1
        yield self
