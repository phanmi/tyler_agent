"""Adapt recordFood arguments and results through the Java operation boundary."""

from tyler_agent.tools.builtin.food_record.verification import Refused, not_recorded, verified_food
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import food_arguments
from tyler_agent.tools.rendering import pretty


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java.

    With verification on, the item is saved only after the run's model accepts it, possibly
    in a regenerated form. A refused item is never sent to Java.
    """
    food = food_arguments(raw)
    if context.settings.verify_food:
        checked = await verified_food(raw, food, context)
        if isinstance(checked, Refused):
            return not_recorded(checked)
        food = checked
    result = await context.operation("food.insert", food)
    return pretty(result["food"])
