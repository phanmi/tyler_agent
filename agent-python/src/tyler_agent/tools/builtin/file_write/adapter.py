"""Adapt writeFile arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import file_arguments


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java."""
    result = await context.operation("file.write", file_arguments(raw, write=True))
    return "Wrote " + str(result["path"])
