"""Adapt recordFood arguments and results through the Java operation boundary."""

from collections.abc import Sequence

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.foods import food_name
from tyler_agent.tools.builtin.food_record.verification import NOT_RECORDED, Refused, verified_food
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import foods_arguments
from tyler_agent.tools.rendering import pretty


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java.

    One call carries every food of the message. With verification on, each food is checked
    before the first one is saved, so a failed model request saves none of them. A food is
    saved only after the run's model accepts it, possibly in a regenerated form; a refused
    food is never sent to Java and is reported beside the saved ones.
    """
    foods = foods_arguments(raw)
    accepted: list[Message] = []
    refused: list[tuple[Message, Refused]] = []
    for food in foods:
        checked = await verified_food(food, context) if context.settings.verify_food else food
        if isinstance(checked, Refused):
            refused.append((food, checked))
        else:
            accepted.append(checked)
    saved = [(await context.operation("food.insert", food))["food"] for food in accepted]
    return recorded(saved, refused)


def recorded(saved: Sequence[Message], refused: Sequence[tuple[Message, Refused]]) -> str:
    """The model-facing result: the foods Java saved, in order, then any refused food with
    the reason it was refused and what to tell the user."""
    result: Message = {"recorded": list(saved)}
    if refused:
        result["notRecorded"] = [
            {"foodName": food_name(food), "reason": refusal.reason} for food, refusal in refused
        ]
        result["error"] = NOT_RECORDED
    return pretty(result)
