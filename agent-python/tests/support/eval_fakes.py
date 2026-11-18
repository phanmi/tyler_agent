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
from support.persistence_worker import ALL_CLEAR, all_clear
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.content_check import VERIFY_FOOD_NUTRITION
from tyler_agent.tools.builtin.food_record.correction import CORRECT_FOOD
from tyler_agent.tools.builtin.food_record.date_check import VERIFY_FOOD_DATES
from tyler_agent.tools.builtin.food_record.name_check import VERIFY_FOOD_LIST
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


def food(**changes: object) -> Message:
    """One food as a model writes it: numbers are JSON numbers."""
    info: dict[str, object] = dict(
        foodName="Grilled chicken breast", amount=200, unit="g", calories=302, date=TODAY
    )
    info.update(changes)
    return {"genericInfo": info, "macroNutrients": dict(protein=61, carbs=0, fat=6.3, fiber=0)}


def food_call(call_id: str = "food", **changes: object) -> ToolCall:
    """The chat model's recordFood call for one food."""
    return ToolCall(call_id, "recordFood", json.dumps({"foods": [food(**changes)]}))


def corrected_food(**changes: object) -> ToolCall:
    """A correctFood answer: one whole food record."""
    return ToolCall("fix", CORRECT_FOOD, json.dumps(food(**changes)))


def nutrition_rejected(reason: str) -> ToolCall:
    """The content check's answer for a food whose nutrition values it rejects."""
    arguments = json.dumps({"valid": False, "reason": reason})
    return ToolCall("nutrition", VERIFY_FOOD_NUTRITION.name, arguments)


def list_without(number: int, reason: str) -> ToolCall:
    """The name and count check's answer that one record does not belong."""
    flagged = [{"number": number, "reason": reason}]
    arguments = json.dumps({"missing": [], "incorrect": flagged, "wrongAmount": []})
    return ToolCall("list", VERIFY_FOOD_LIST.name, arguments)


# The checks recordFood always runs, and those it runs with the nutrition check on.
ALWAYS = (VERIFY_FOOD_LIST.name, VERIFY_FOOD_DATES.name)
WITH_NUTRITION = (*ALWAYS, VERIFY_FOOD_NUTRITION.name)


def cleared(checks: Sequence[str]) -> list[AssistantTurn]:
    """The answers of a model that finds nothing wrong, one for each of these checks."""
    return [turn(ToolCall(check, check, ALL_CLEAR[check])) for check in checks]


def turn(*calls: ToolCall, text: str = "", usage: Usage | None = USAGE) -> AssistantTurn:
    return AssistantTurn(tuple(calls), text, usage=usage)


def session(opener: ProviderFactory) -> Session:
    """A session whose clock advances one second per reading, so durations are exact."""
    ticks = count()
    return Session({TARGET.provider: KEY}, opener, lambda: float(next(ticks)))


class RuleProvider:
    """Answers like a well-behaved model for any number of chats: record the food once,
    pass every check, then reply. `opened` counts the chats that reached it, and
    `keys` holds the API key each of them was opened with."""

    def __init__(self) -> None:
        self.opened = 0
        self.keys: list[str] = []

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        cleared = all_clear(tools)
        if cleared is not None:
            return replace(cleared, usage=USAGE)
        if any(isinstance(entry, ToolResult) for entry in transcript):
            return turn(text="Recorded.")
        return turn(food_call())

    @asynccontextmanager
    async def open(self, value: Message) -> AsyncIterator[Provider]:
        self.opened += 1
        self.keys.append(value["apiKey"])
        yield self
