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
from tyler_agent.tools.builtin.food_record.content_check import VERIFY_FOOD_NUTRITION
from tyler_agent.tools.builtin.food_record.date_check import CORRECT_FOOD_DATES, VERIFY_FOOD_DATES
from tyler_agent.tools.builtin.food_record.name_check import VERIFY_FOOD_LIST
from tyler_agent.worker import main

# What a model that finds nothing wrong answers to each of recordFood's checks. The other test
# fakes take it from here, because this entry point runs as a script and can import only
# tyler_agent.
ALL_CLEAR = {
    VERIFY_FOOD_LIST.name: '{"missing": [], "incorrect": [], "wrongAmount": []}',
    VERIFY_FOOD_DATES.name: '{"wrong": []}',
    VERIFY_FOOD_NUTRITION.name: '{"valid": true, "reason": "Plausible."}',
}


# The date check never accepts a food with no date, so the scenario that leaves the date out
# is asked for one. That scenario's call holds one food, so the answer is for food 1.
FIRST_FOOD_DATED = ToolCall(
    CORRECT_FOOD_DATES.name,
    CORRECT_FOOD_DATES.name,
    '{"dates": [{"number": 1, "date": "2026-10-19"}]}',
)


def all_clear(tools: Sequence[ToolDefinition]) -> AssistantTurn | None:
    """The answer that finds nothing wrong, when the request is one of recordFood's checks;
    None for any other request."""
    if len(tools) != 1 or tools[0].name not in ALL_CLEAR:
        return None
    name = tools[0].name
    return AssistantTurn((ToolCall(name, name, ALL_CLEAR[name]),), "")


def food(**changes: object) -> Message:
    info = dict(foodName="Oats", amount=100, unit="g", calories=389, date="2026-10-19")
    info.update(changes)
    macros = dict(protein=17, carbs=66, fat=7, fiber=10)
    return dict(genericInfo=info, macroNutrients=macros)


def food_call(**changes: object) -> ToolCall:
    return tool("recordFood", dict(foods=[food(**changes)]))


def food_list_call(*foods: Message) -> ToolCall:
    """One recordFood call with several foods, which Java saves in one transaction."""
    return tool("recordFood", dict(foods=list(foods)))


def tool(name: str, arguments: Message) -> ToolCall:
    return ToolCall(name, name, json.dumps(arguments))


def calls_for(scenario: str) -> tuple[ToolCall, ...]:
    """Return model-authored inputs; Java services still decide domain validity."""
    cases = {
        "food": (food_call(),),
        "two_food": (food_call(), food_call()),
        "food_list": (food_list_call(food(), food(foodName="Banana")),),
        "second_food_negative": (food_list_call(food(), food(amount=-1)),),
        "before_write": (tool("getCurrentTime", {}), food_call()),
        "negative_food": (food_call(amount=-1),),
        "invalid_date": (food_call(date="2026-02-30"),),
        "missing_food": (tool("recordFood", dict(foods=[{}])),),
        "plan": (
            tool(
                "generateWorkoutPlan",
                dict(startDate="2026-10-19", goal="general_fitness", equipment="bodyweight"),
            ),
        ),
        "missing_plan": (tool("generateWorkoutPlan", {}),),
        # Arguments that do not match the tool schemas; each must go back to the model unrun.
        "food_wrong_types": (food_call(foodName=True, unit=False, amount="1", calories="2"),),
        "plan_preview": (
            tool(
                "generateWorkoutPlan",
                dict(
                    startDate="2026-10-19",
                    goal="general_fitness",
                    equipment="bodyweight",
                    preview=True,
                ),
            ),
        ),
        "file_object_content": (
            tool("writeFile", dict(path="notes.txt", content={"unexpected": True})),
        ),
        "file_array_content": (tool("writeFile", dict(path="new-note.txt", content=[])),),
        "file_numeric_path": (tool("readFile", dict(path=123)),),
        "file_failure": (tool("readFile", dict(path="missing.txt")),),
        "food_then_failure": (food_call(),),
        "unanswered_check": (food_call(),),
        "saved_then_unanswered_check": (food_call(), food_call()),
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
        # Reported back so Java can check whether the run's nutrition check was switched on.
        self.nutrition_checks = 0
        # Counted for the scenario that answers only its first recordFood call's date check.
        self.date_checks = 0

    def _leaves_unanswered(self, check: str) -> bool:
        """Whether this scenario answers the check with text, which no check can use."""
        if self.scenario == "unanswered_check":
            return True
        if self.scenario != "saved_then_unanswered_check" or check != VERIFY_FOOD_DATES.name:
            return False
        # The first call's date check is answered, so that call saves its food. Every date
        # check after it belongs to the second call, which asks until it gives up.
        self.date_checks += 1
        return self.date_checks > 1

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        if [tool.name for tool in tools] == [VERIFY_FOOD_NUTRITION.name]:
            self.nutrition_checks += 1
        cleared = all_clear(tools)
        if cleared is not None and self._leaves_unanswered(tools[0].name):
            # Text where a check's tool call is required: an answer that cannot be used.
            return AssistantTurn((), "Everything looks right.")
        if cleared is not None:
            # recordFood checks its foods first; pass them so Java services decide validity.
            return cleared
        if [tool.name for tool in tools] == [CORRECT_FOOD_DATES.name]:
            return AssistantTurn((FIRST_FOOD_DATED,), "")
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
            historySize=self.history_size,
            outputs=outputs,
            provider=self.provider,
            model=model,
            nutritionChecks=self.nutrition_checks,
        )
        return AssistantTurn((), json.dumps(reply))


@asynccontextmanager
async def provider(value: Message) -> AsyncIterator[Provider]:
    yield PersistenceProvider(value)


if __name__ == "__main__":
    raise SystemExit(main(provider))
