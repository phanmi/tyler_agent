"""Adapt generateWorkoutPlan arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import Operation
from tyler_agent.tools.parsing import plan_arguments
from tyler_agent.tools.rendering import pretty


async def execute(raw: str, operation: Operation) -> str:
    """Preserve the existing adapter contract; domain rules remain in Java."""
    result = await operation("workout_plan.generate_save", plan_arguments(raw))
    return pretty(result["plan"])
