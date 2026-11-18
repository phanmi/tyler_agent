"""Adapt recordFood arguments and results through the Java operation boundary."""

from collections.abc import Sequence

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.checks import checked_foods
from tyler_agent.tools.builtin.food_record.foods import Removed, food_name
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import foods_arguments
from tyler_agent.tools.rendering import pretty

# Said beside the records the checks left out, so the chat model does not record them again.
NOT_RECORDED = (
    "The records under notRecorded were left out of the diary, each for the reason given. "
    "Do not call recordFood for them again unless the user gives more detail."
)


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java.

    One call carries every food of the message. The checks run on all of them before the
    first one is saved, so a check that fails the run saves none of them. What is saved is
    the list the checks leave: a food they added or corrected is saved in that form, and a
    record they removed is never sent to Java and is reported beside the saved ones.
    """
    checked = await checked_foods(foods_arguments(raw), context)
    saved = [(await context.operation("food.insert", food))["food"] for food in checked.foods]
    return recorded(saved, checked.removed)


def recorded(saved: Sequence[Message], removed: Sequence[Removed]) -> str:
    """The model-facing result: the foods Java saved, in order, then any record the checks
    left out with the reason, and a note that it should not be recorded again."""
    result: Message = {"recorded": list(saved)}
    if removed:
        result["notRecorded"] = [
            {"foodName": food_name(entry.food), "reason": entry.reason} for entry in removed
        ]
        result["note"] = NOT_RECORDED
    return pretty(result)
