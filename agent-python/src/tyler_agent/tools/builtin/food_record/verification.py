"""Check a food record against the conversation before it is saved, regenerating rejects.

The run's own model reviews each candidate record. It sees the conversation as text, today's
date from Java, and the record, and answers through a verdict tool. A rejected record is
regenerated with the verdict's reason and checked again; a record rejected twice is refused.
Nothing here writes: the adapter saves only a record this module returns.

The model checks its own output, so it catches mismatches with the conversation (wrong food,
amount or date) better than errors in its own nutrition estimates.
"""

import json
import logging
from dataclasses import dataclass
from importlib.resources import files

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import (
    ArgumentsRejected,
    arguments,
    food_arguments,
    object_value,
    text_value,
)
from tyler_agent.transcript import AssistantTurn, ToolDefinition, UserTurn

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
inconsistent with each other.

Today's date and time: {today}

The conversation, oldest message first:
{conversation}

The food record to check:
{record}

Call {tool} exactly once."""

REGENERATE_PROMPT = """\
A food diary record was rejected before it was saved. Write a corrected record for the same food.

Why it was rejected: {reason}

Today's date and time: {today}

The conversation, oldest message first:
{conversation}

The rejected record:
{record}

Call {tool} exactly once with the corrected record. Estimate nutrition values from the food and \
amount when the user did not give them."""

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
    """The record reached MAX_REJECTIONS; `reason` is the last usable rejection reason."""

    reason: str


@dataclass(frozen=True)
class Candidate:
    """One record under review: the model's recordFood arguments and their parsed input."""

    raw: str
    food: Message


def parse_verdict(turn: AssistantTurn) -> Verdict:
    """Accept exactly one verdict call with a boolean and a text reason; anything else rejects.

    The verdict tool is offered but not forced (Claude cannot force a tool while thinking, and
    DeepSeek's tool calls are not strict), so a text reply or a malformed call is expected
    occasionally.
    """
    if len(turn.calls) != 1 or turn.calls[0].name != VERDICT_TOOL.name:
        return MALFORMED_VERDICT
    try:
        value = object_value(arguments(turn.calls[0].arguments), ("valid", "reason"))
        reason = text_value(value.get("reason"))
    except ArgumentsRejected:
        return MALFORMED_VERDICT
    valid = value.get("valid")
    if not isinstance(valid, bool) or reason is None:
        return MALFORMED_VERDICT
    return Verdict(valid, reason)


def not_recorded(refused: Refused) -> str:
    """The model-facing result for a refused record, which was never sent to Java."""
    return json.dumps({"error": NOT_RECORDED, "reason": refused.reason}, ensure_ascii=False)


@dataclass(frozen=True)
class Review:
    """The model requests about one food item, which share the run context and today's date."""

    context: ToolContext
    today: str

    async def verdict(self, record: str) -> Verdict:
        prompt = VERIFY_PROMPT.format(
            today=self.today,
            conversation=self._conversation(),
            record=record,
            tool=VERDICT_TOOL.name,
        )
        return parse_verdict(await self.context.respond((UserTurn(prompt),), (VERDICT_TOOL,)))

    async def regenerated(self, rejected: str, reason: str) -> Candidate | None:
        """Ask for one corrected recordFood call; None when the answer doesn't match it."""
        tool = record_food_tool()
        prompt = REGENERATE_PROMPT.format(
            reason=reason,
            today=self.today,
            conversation=self._conversation(),
            record=rejected,
            tool=tool.name,
        )
        turn = await self.context.respond((UserTurn(prompt),), (tool,))
        if len(turn.calls) != 1 or turn.calls[0].name != tool.name:
            return None
        try:
            return Candidate(turn.calls[0].arguments, food_arguments(turn.calls[0].arguments))
        except ArgumentsRejected:
            return None

    def _conversation(self) -> str:
        """JSON keeps message boundaries unambiguous whatever the messages contain."""
        return json.dumps(
            [
                {
                    "role": "user" if isinstance(turn, UserTurn) else "assistant",
                    "content": turn.text,
                }
                for turn in self.context.conversation
            ],
            ensure_ascii=False,
            indent=2,
        )


async def verified_food(raw: str, food: Message, context: ToolContext) -> Message | Refused:
    """Return the record to save, regenerated if needed, or why nothing should be saved.

    `raw` is the chat model's recordFood arguments and `food` their parsed food.insert input.
    This requests only the read-only time operation and model calls. Provider failures and
    cancellation propagate, so nothing is saved after them. A regenerated number beyond the
    protocol bound fails the run, as it does for the chat model's own call.
    """
    review = Review(context, str((await context.operation("time.current", {}))["text"]))
    candidate: Candidate | None = Candidate(raw, food)
    rejected, reason = raw, ""
    for rejections in range(MAX_REJECTIONS):
        if rejections:
            candidate = await review.regenerated(rejected, reason)
        if candidate is None:
            # A correction that doesn't match recordFood counts as a rejection; the reason stands.
            continue
        verdict = await review.verdict(candidate.raw)
        if verdict.valid:
            log.info("Food record verified: rejections=%d", rejections)
            return candidate.food
        rejected, reason = candidate.raw, verdict.reason
    log.info("Food record refused by verification: rejections=%d", MAX_REJECTIONS)
    return Refused(reason)


def record_food_tool() -> ToolDefinition:
    """recordFood's own definition, so a regenerated record has the shape the chat model uses."""
    package = files(__name__.rpartition(".")[0])
    return ToolDefinition.from_definition(
        json.loads(package.joinpath("definition.json").read_text("utf-8"))
    )
