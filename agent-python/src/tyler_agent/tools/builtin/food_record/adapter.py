"""Adapt recordFood arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import food_arguments
from tyler_agent.tools.rendering import pretty


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java."""
    result = await context.operation("food.insert", food_arguments(raw))
    return pretty(result["food"])
