"""Adapt generateWorkoutPlan arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import plan_arguments
from tyler_agent.tools.rendering import pretty


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java."""
    result = await context.operation("workout_plan.generate_save", plan_arguments(raw))
    return pretty(result["plan"])
