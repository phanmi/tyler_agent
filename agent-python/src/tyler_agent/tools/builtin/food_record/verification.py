"""Check a food record against the conversation before it is saved, regenerating rejects.

The run's own model reviews each candidate record. It sees the conversation as text, today's
date from Java, and the record, and answers through a verdict tool. A rejected record is
regenerated with the verdict's reason and checked again; a record rejected twice is refused.
Nothing here writes: the adapter saves only a record this module returns.

The model checks its own output, so it catches mismatches with the conversation (wrong food,
amount or date) better than errors in its own nutrition estimates. It sees no tool results, so
a record whose facts came from one, such as a file the chat model read, is likely to be refused.
"""

import json
import logging
from dataclasses import dataclass
from importlib.resources import files

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.time_current.adapter import current_time
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.discovery import read_definition
from tyler_agent.tools.parsing import (
    ArgumentsRejected,
    arguments,
    food_arguments,
    object_value,
    text_value,
)
from tyler_agent.transcript import AssistantTurn, ToolCall, ToolDefinition, UserTurn

log = logging.getLogger(__name__)

# A record is refused at this many rejections, so one item costs one to three model requests.
MAX_REJECTIONS = 2

VERDICT_TOOL = ToolDefinition(
    "submitFoodVerdict",
    "Report whether a food diary record faithfully records what the user said they ate.",
    {
        "type": "object",
        "properties": {
            "valid": {
                "type": "boolean",
                "description": "True only if every field of the record is correct.",
            },
            "reason": {
                "type": "string",
                "description": "One or two sentences. When rejecting, say what is wrong and "
                "what it should be.",
            },
        },
        "required": ["valid", "reason"],
        "additionalProperties": False,
    },
)

VERIFY_PROMPT = """\
You check one food diary record before it is saved. Decide whether it faithfully records what \
the user said they ate.

Reject the record if any of these is wrong:
- The food is not something the user said they ate, or is a different food.
- The amount or unit does not match what the user said. When the user gave no amount, accept a \
reasonable estimate.
- The date does not match when the user said they ate it, counted from today's date below. When \
the user gave no date, the date is today.
- Calories, protein, carbs, fat or fiber are implausible for that food and amount, or \
inconsistent with each other. Values the user stated themselves are correct as given, even if \
they look unusual.

Today's date and time: {today}

The conversation, oldest message first:
{conversation}

The food record to check:
{record}{correction}

Call {tool} exactly once."""

# Added for a regenerated record, so a correction cannot swap in another food the user mentioned.
CORRECTION_NOTE = """

This record replaces the rejected record below. Also reject it if it is about a different food \
than the rejected record:
{replaced}"""

REGENERATE_PROMPT = """\
A food diary record was rejected before it was saved. Write a corrected record for the same food.

Why it was rejected: {reason}

Today's date and time: {today}

The conversation, oldest message first:
{conversation}

The rejected record:
{record}

Call {tool} exactly once with the corrected record. Keep every value the user stated. Estimate \
nutrition values from the food and amount when the user did not give them."""

NOT_RECORDED = (
    "Not recorded: this food item failed verification, so nothing was saved. Tell the user it "
    "was not recorded and why. Do not call recordFood for it again unless the user gives more "
    "detail."
)


@dataclass(frozen=True)
class Verdict:
    valid: bool
    reason: str


# Any answer that is not one well-formed verdict call becomes this, so it can never accept.
MALFORMED_VERDICT = Verdict(False, "The check returned no usable verdict.")


@dataclass(frozen=True)
class Refused:
    """The record reached MAX_REJECTIONS; `reason` is the last reason a verdict gave."""

    reason: str


@dataclass(frozen=True)
class Candidate:
    """One record under review: the model's recordFood arguments, their parsed input, and
    the rejected arguments it corrects, if it is a regeneration."""

    raw: str
    food: Message
    replaces: str | None = None


def single_call(turn: AssistantTurn, tool: ToolDefinition) -> ToolCall | None:
    """The turn's call when it is exactly one call of `tool`; a text reply, another tool, or
    several calls give None."""
    if len(turn.calls) != 1 or turn.calls[0].name != tool.name:
        return None
    return turn.calls[0]


def parse_verdict(turn: AssistantTurn) -> Verdict:
    """Accept exactly one verdict call with a boolean and a text reason; anything else, or a
    rejection that gives no reason, is MALFORMED_VERDICT.

    The verdict tool is offered but not forced (Claude cannot force a tool while thinking, and
    DeepSeek's tool calls are not strict), so a text reply or a malformed call is expected
    occasionally.
    """
    call = single_call(turn, VERDICT_TOOL)
    if call is None:
        return MALFORMED_VERDICT
    try:
        value = object_value(arguments(call.arguments), ("valid", "reason"))
        reason = text_value(value.get("reason"))
    except ArgumentsRejected:
        return MALFORMED_VERDICT
    valid = value.get("valid")
    if not isinstance(valid, bool) or reason is None or not (valid or reason.strip()):
        return MALFORMED_VERDICT
    return Verdict(valid, reason)


def not_recorded(refused: Refused) -> str:
    """The model-facing result for a refused record, which was never sent to Java."""
    return json.dumps({"error": NOT_RECORDED, "reason": refused.reason}, ensure_ascii=False)


def conversation_text(context: ToolContext) -> str:
    """JSON keeps message boundaries unambiguous whatever the messages contain."""
    return json.dumps(
        [
            {"role": "user" if isinstance(turn, UserTurn) else "assistant", "content": turn.text}
            for turn in context.conversation
        ],
        ensure_ascii=False,
    )


def record_food_tool() -> ToolDefinition:
    """recordFood's own definition, so a regenerated record has the shape the chat model uses."""
    return ToolDefinition.from_definition(read_definition(files(__name__.rpartition(".")[0])))


@dataclass(frozen=True)
class Review:
    """The model requests about one food item, which share the run context, today's date and
    the conversation text."""

    context: ToolContext
    today: str
    conversation: str

    async def verdict(self, candidate: Candidate) -> Verdict:
        correction = (
            ""
            if candidate.replaces is None
            else CORRECTION_NOTE.format(replaced=candidate.replaces)
        )
        prompt = VERIFY_PROMPT.format(
            today=self.today,
            conversation=self.conversation,
            record=candidate.raw,
            correction=correction,
            tool=VERDICT_TOOL.name,
        )
        return parse_verdict(await self.context.respond((UserTurn(prompt),), (VERDICT_TOOL,)))

    async def regenerated(self, rejected: Candidate, reason: str) -> Candidate | None:
        """Ask for one corrected recordFood call; None when the answer is not a recordFood call
        that Java could be sent, including a number beyond the protocol bound."""
        tool = record_food_tool()
        prompt = REGENERATE_PROMPT.format(
            reason=reason,
            today=self.today,
            conversation=self.conversation,
            record=rejected.raw,
            tool=tool.name,
        )
        call = single_call(await self.context.respond((UserTurn(prompt),), (tool,)), tool)
        if call is None:
            return None
        try:
            return Candidate(call.arguments, food_arguments(call.arguments), rejected.raw)
        except (ArgumentsRejected, AgentFailure):
            # Parsing raises AgentFailure only for a number beyond the protocol bound. The user
            # never supplied this value, so it is a failed correction, not a failed chat.
            return None


async def verified_food(raw: str, food: Message, context: ToolContext) -> Message | Refused:
    """Return the record to save, regenerated if needed, or why nothing should be saved.

    `raw` is the chat model's recordFood arguments and `food` their parsed food.insert input.
    Each of these counts as one rejection: a verdict that rejects, an unusable verdict, and a
    regeneration that is not a usable recordFood call. A rejected record is regenerated; after
    an unusable verdict the same record is checked again, because there is nothing to correct.

    This requests only the read-only time operation and model calls. Provider failures and
    cancellation propagate, so nothing is saved after them.
    """
    review = Review(context, await current_time(context.operation), conversation_text(context))
    candidate = Candidate(raw, food)
    rejections, reason = 0, MALFORMED_VERDICT.reason
    while rejections < MAX_REJECTIONS:
        verdict = await review.verdict(candidate)
        if verdict.valid:
            log.info("Food record verified: rejections=%d", rejections)
            return candidate.food
        rejections += 1
        if verdict is MALFORMED_VERDICT:
            continue
        reason = verdict.reason
        if rejections < MAX_REJECTIONS:
            correction = await review.regenerated(candidate, reason)
            if correction is None:
                rejections += 1
            else:
                candidate = correction
    log.info("Food record refused by verification: rejections=%d", rejections)
    return Refused(reason)
