"""Ask the run's model one question through one tool, and what the food checks judge against.

Each check and each correction is its own request: one user turn and one offered tool. The
chat model never sees these tools, so it cannot skip a check. A tool is offered, not forced
(Claude cannot force a tool while thinking, and DeepSeek's tool calls are not strict), so an
answer can be unusable: a text reply, another tool, several calls, or arguments that do not
fit the tool. An unusable answer is never read as "nothing is wrong"; the question is asked
again.
"""

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable, Container, Iterable
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from tyler_agent.failures import AgentFailure
from tyler_agent.tools.builtin.time_current.adapter import current_time
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import ArgumentsRejected, object_value, text_value
from tyler_agent.transcript import AssistantTurn, ToolCall, ToolDefinition, UserTurn

log = logging.getLogger(__name__)

# How many times a question is asked before giving up. The foods cannot be saved without an
# answer to a required question, so it gets the first ask and three more. The others get the
# first ask and one more.
REQUIRED_ATTEMPTS = 4
OPTIONAL_ATTEMPTS = 2

# How many of a check's questions are being asked at the same time. One recordFood call can
# carry any number of foods, and the providers do not retry, so without a limit a long list
# would send every question at once and one rate-limit error would fail the run.
MAX_QUESTIONS_TOGETHER = 8

# How every prompt shows the model the conversation and the run's earlier tool calls.
EVIDENCE_TEXT = """\
The conversation, oldest message first. The last message is the user's latest:
{conversation}

The tool calls already made in this chat, oldest first, with their results:
{earlier_calls}"""


@dataclass(frozen=True)
class Question[T]:
    """One request to the model: the prompt, the only tool offered, and how to read the
    arguments of that tool's call. `parse` raises ArgumentsRejected for arguments that do not
    fit the tool, which makes the answer unusable."""

    prompt: str
    tool: ToolDefinition
    parse: Callable[[str], T]


@dataclass(frozen=True)
class Evidence:
    """What the checks of one recordFood call judge its foods against, read once and shared:
    Java's date and time, the conversation, and the tool calls the run finished earlier.

    The conversation and the calls are JSON text, so whatever a message or a tool result
    contains stays data inside a prompt and cannot change what a check is asked to do.
    """

    today: str
    conversation: str
    earlier_calls: str

    @property
    def text(self) -> str:
        """The conversation and the earlier calls as a prompt shows them. Today's date is
        not part of it, because the name and count check is not shown the date."""
        return EVIDENCE_TEXT.format(
            conversation=self.conversation, earlier_calls=self.earlier_calls
        )


def conversation_text(context: ToolContext) -> str:
    """JSON keeps message boundaries unambiguous whatever the messages contain."""
    return json.dumps(
        [
            {"role": "user" if isinstance(turn, UserTurn) else "assistant", "content": turn.text}
            for turn in context.conversation
        ],
        ensure_ascii=False,
    )


def earlier_calls_text(context: ToolContext) -> str:
    """The run's finished tool calls as JSON, each with its arguments and result as the text
    the model wrote and was given."""
    return json.dumps(
        [
            {"tool": call.name, "arguments": call.arguments, "result": call.result}
            for call in context.earlier_calls
        ],
        ensure_ascii=False,
    )


async def evidence(context: ToolContext) -> Evidence:
    """Read the evidence for this call. It requests only the read-only time operation."""
    return Evidence(
        await current_time(context.operation),
        conversation_text(context),
        earlier_calls_text(context),
    )


def single_call(turn: AssistantTurn, tool: ToolDefinition) -> ToolCall | None:
    """The turn's call when it is exactly one call of `tool`; a text reply, another tool, or
    several calls give None."""
    if len(turn.calls) != 1 or turn.calls[0].name != tool.name:
        return None
    return turn.calls[0]


async def ask[T](context: ToolContext, question: Question[T], attempts: int) -> T | None:
    """Ask until an answer can be used, at most `attempts` times; None when none could be.

    Provider failures and cancellation propagate. The log names only the tool and the
    attempt, never the prompt or the answer.
    """
    for attempt in range(1, attempts + 1):
        turn = await context.respond((UserTurn(question.prompt),), (question.tool,))
        call = single_call(turn, question.tool)
        if call is not None:
            try:
                return question.parse(call.arguments)
            except ArgumentsRejected:
                pass
        log.warning("Food check answer unusable: tool=%s attempt=%d", question.tool.name, attempt)
    return None


async def must_ask[T](context: ToolContext, question: Question[T]) -> T:
    """Ask a question the foods cannot be saved without. When no answer can be used after
    REQUIRED_ATTEMPTS, the run fails, so nothing is saved.

    The protocol has no failure code of its own for this, so it is a provider failure.
    """
    answer = await ask(context, question, REQUIRED_ATTEMPTS)
    if answer is None:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    return answer


async def together[S, T](answer_for: Callable[[S], Awaitable[T]], items: Iterable[S]) -> list[T]:
    """Ask about each item at the same time, at most MAX_QUESTIONS_TOGETHER at once, and
    return the answers in the items' order. An item beyond the limit is asked about when an
    earlier one has its answer.

    If one fails, or the run is cancelled, the others are cancelled and awaited before the
    failure propagates, so no request outlives the check that sent it.
    """
    free = asyncio.Semaphore(MAX_QUESTIONS_TOGETHER)

    async def in_turn(item: S) -> T:
        async with free:
            return await answer_for(item)

    tasks = [asyncio.ensure_future(in_turn(item)) for item in items]
    try:
        return await asyncio.gather(*tasks)
    except BaseException:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        raise


# The schema of the number that points an entry of an answer at a food.
FOOD_NUMBER: dict[str, Any] = {
    "type": "integer",
    "description": "The record's number in the list.",
}

# The schema of one entry in a check's answer that points at a food and says what is wrong.
FLAGGED_FOOD: dict[str, Any] = {
    "type": "object",
    "properties": {
        "number": FOOD_NUMBER,
        "reason": {
            "type": "string",
            "description": "One or two sentences: what is wrong and what it should be.",
        },
    },
    "required": ["number", "reason"],
    "additionalProperties": False,
}


def numbered_texts(value: Any, field: str, allowed: Container[int]) -> dict[int, str]:
    """Read a list of `{number, <field>}` entries from an answer as texts keyed by food number.

    Each number must be one of `allowed` and must not repeat, and each text must not be
    blank. Anything else is rejected.
    """
    if not isinstance(value, list):
        raise ArgumentsRejected
    texts: dict[int, str] = {}
    for item in value:
        entry = object_value(item, ("number", field))
        number, text = entry.get("number"), text_value(entry.get(field))
        # Only a JSON number equal to an allowed one passes: true is not read as 1, 1.5 is
        # not rounded to it, and a huge exponent is never expanded into digits.
        if not isinstance(number, Decimal) or number not in allowed or int(number) in texts:
            raise ArgumentsRejected
        if text is None or not text.strip():
            raise ArgumentsRejected
        texts[int(number)] = text
    return texts


def flagged_foods(value: Any, count: int) -> dict[int, str]:
    """Read a list of `{number, reason}` from a check's answer as reasons keyed by food
    number, for a list of `count` foods: the numbers the model was shown are 1 to `count`."""
    return numbered_texts(value, "reason", range(1, count + 1))
