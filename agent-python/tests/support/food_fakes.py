"""Scripted Java and model doubles for the recordFood tests, and the foods they record."""

import asyncio
import json
from collections.abc import Awaitable, Callable, Iterable, Sequence
from dataclasses import replace

from support.agent_fakes import DEFAULT_SETTINGS, fake_context
from support.persistence_worker import all_clear
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.asking import Evidence, evidence
from tyler_agent.tools.builtin.food_record.foods import FoodList
from tyler_agent.tools.contracts import CompletedCall, Operation, RunSettings, ToolContext
from tyler_agent.tools.parsing import food_arguments
from tyler_agent.transcript import (
    AssistantTurn,
    ToolCall,
    ToolDefinition,
    TranscriptEntry,
    UserTurn,
)

# One check of a recordFood call's foods, and a food a check's answer points at with a reason.
type Check = Callable[[FoodList, Evidence, ToolContext], Awaitable[FoodList]]
type Flag = tuple[int, str]

TODAY = "2026-11-10 12:30:00 EST"
# The parts of the rule for values the user did not state that recordFood's description gives
# and the checks repeat, so a change to one copy that leaves out a source fails a test (#105).
SOURCE_PHRASES = (
    "packaged or restaurant food",
    "the brand's published nutrition",
    "for any other food",
    "USDA FoodData Central values for that food",
    "the closest generic food in FoodData Central",
)
# The examples of the unit rule that recordFood's description gives and the checks repeat, so
# the chat model and the checks keep the same units (#104).
UNIT_PHRASES = ('"cup (240 ml)"', '"one 2 oz bar"')
CONVERSATION = (
    UserTurn("I had oats for breakfast."),
    AssistantTurn((), "Noted."),
    UserTurn("I ate 200 g of chicken breast for lunch today."),
)
MACROS = dict(protein=62, carbs=0, fat=7, fiber=0)
# No run switches the name and count check on, so the tests that keep it working ask for it.
NAMES_ON = RunSettings(check_names=True)


def food_raw(**changes: object) -> str:
    """One food as a model writes it. `changes` replace its general info, except `macros`,
    which replaces its nutrition values."""
    macros = changes.pop("macros", MACROS)
    info: dict[str, object] = dict(
        foodName="Chicken breast", amount=200, unit="g", calories=330, date="2026-11-10"
    )
    info.update(changes)
    return json.dumps(dict(genericInfo=info, macroNutrients=macros))


def foods_raw(*foods: str) -> str:
    """The chat model's recordFood arguments: every food of the message in one list."""
    return '{"foods": [' + ", ".join(foods or (food_raw(),)) + "]}"


class Java:
    """Record operation requests; answer the clock, read the workspace files it is given, and
    echo saved food like the service does.

    `clocks` gives each clock request its own text, so a test can tell which one a prompt used.
    """

    def __init__(self, clocks: Sequence[str] = (), files: dict[str, str] | None = None) -> None:
        self.requests: list[tuple[str, Message]] = []
        self.clocks = list(clocks)
        self.files = files or {}

    async def __call__(self, name: str, value: Message) -> Message:
        self.requests.append((name, value))
        if name == "time.current":
            return {"text": self.clocks.pop(0) if self.clocks else TODAY}
        if name == "file.read":
            return {"content": self.files[value["path"]]}
        assert name == "food.insert"
        return {"food": value["food"]}

    def saved(self) -> list[Message]:
        return [value for name, value in self.requests if name == "food.insert"]


class Model:
    """Answer the requests recordFood makes from a script, recording each prompt, tool list,
    and how many items Java had saved when the request was made."""

    def __init__(self, java: Java, replies: list[AssistantTurn | Exception]) -> None:
        self.java = java
        self.replies = replies
        self.prompts: list[str] = []
        self.tools: list[list[str]] = []
        self.saved_at_request: list[int] = []

    async def __call__(
        self, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        (turn,) = transcript
        assert isinstance(turn, UserTurn)
        self.prompts.append(turn.text)
        self.tools.append([tool.name for tool in tools])
        self.saved_at_request.append(len(self.java.saved()))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply


def answer(tool: str, raw: str) -> AssistantTurn:
    """A model reply that calls one tool with these arguments."""
    return AssistantTurn((ToolCall("answer", tool, raw),), "")


def flags(found: Iterable[Flag]) -> list[Message]:
    """The entries of a check's answer that point at foods by number."""
    return [{"number": number, "reason": reason} for number, reason in found]


def food_list(*raws: str) -> FoodList:
    return FoodList(tuple(food_arguments(raw) for raw in raws))


def check_context(
    model: Model,
    earlier: Sequence[CompletedCall] = (),
    conversation: tuple[UserTurn | AssistantTurn, ...] = CONVERSATION,
    settings: RunSettings = DEFAULT_SETTINGS,
) -> ToolContext:
    """A run context whose model is the scripted one, with these earlier tool calls."""
    context = fake_context(model.java, model, conversation, settings)
    return replace(context, earlier_calls=tuple(earlier))


def run_check(
    check: Check,
    replies: Sequence[AssistantTurn | Exception],
    *raws: str,
    earlier: Sequence[CompletedCall] = (),
    conversation: tuple[UserTurn | AssistantTurn, ...] = CONVERSATION,
    settings: RunSettings = DEFAULT_SETTINGS,
) -> tuple[FoodList, Model]:
    """Run one check on these foods with a scripted model."""
    model = Model(Java(), list(replies))
    context = check_context(model, earlier, conversation, settings)

    async def scenario() -> FoodList:
        return await check(food_list(*raws), await evidence(context), context)

    return asyncio.run(scenario()), model


async def passing(
    transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
) -> AssistantTurn:
    """A model that finds nothing wrong with any food, and is asked nothing else."""
    cleared = all_clear(tools)
    assert cleared is not None, "A tool made a model request this test does not expect"
    return cleared


def food_context(operation: Operation, settings: RunSettings = DEFAULT_SETTINGS) -> ToolContext:
    """A run context for tests of recordFood's parsing and output: every check passes, and the
    clock the checks read is answered here, so `operation` sees only what the test is about."""

    async def with_clock(name: str, value: Message) -> Message:
        if name == "time.current":
            return {"text": TODAY}
        return await operation(name, value)

    return fake_context(with_clock, passing, settings=settings)
