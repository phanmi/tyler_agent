"""Adapt recordFood arguments and results through the Java operation boundary."""

from collections.abc import Sequence
from dataclasses import dataclass

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.checks import checked_foods
from tyler_agent.tools.builtin.food_record.foods import Change, Finding
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import foods_arguments
from tyler_agent.tools.rendering import pretty

# Said beside the records the checks left out, so the chat model tells the user about them
# and does not record them again. A check can be wrong to leave a record out, and the user
# can put that right only when the reply says so.
NOT_RECORDED = (
    "The records under notRecorded were left out of the diary, each for the reason given. "
    "Tell the user which records were not recorded and why. Do not call recordFood for them "
    "again unless the user gives more detail."
)


@dataclass(frozen=True)
class Recorded:
    """What one recordFood call did: the foods Java saved, in order, and what the checks
    found on the way."""

    saved: tuple[Message, ...]
    findings: tuple[Finding, ...]


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java."""
    return rendered(await record_foods(raw, context))


async def record_foods(raw: str, context: ToolContext) -> Recorded:
    """Check the foods of one call and save the ones the checks leave.

    One call carries every food of the message. The checks run on all of them before any
    is saved, so a check that fails the run saves none of them. What is saved is the list
    the checks leave: a food they added or corrected is saved in that form, and a record
    they removed is never sent to Java.
    """
    checked = await checked_foods(foods_arguments(raw), context)
    return Recorded(tuple(await saved_foods(checked.foods, context)), checked.findings)


async def saved_foods(foods: Sequence[Message], context: ToolContext) -> list[Message]:
    """Save the foods in one Java operation and return them as Java saved them, in order.

    Java saves the list in one transaction: when it rejects one food, none is saved. The
    operation takes at least one food, so when the checks left none there is no request.
    """
    if not foods:
        return []
    result = await context.operation("food.insert_all", {"foods": [food["food"] for food in foods]})
    return [entry["food"] for entry in result["foods"]]


def rendered(recorded: Recorded) -> str:
    """The model-facing result: the foods Java saved, in order, then any record the checks
    left out with the reason, and a note to tell the user and not to record it again."""
    result: Message = {"recorded": list(recorded.saved)}
    left_out = [finding for finding in recorded.findings if finding.change is Change.REMOVED]
    if left_out:
        result["notRecorded"] = [
            {"foodName": finding.food, "reason": finding.reason} for finding in left_out
        ]
        result["note"] = NOT_RECORDED
    return pretty(result)
