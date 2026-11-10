"""Adapt readFile arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import file_arguments


async def execute(raw: str, context: ToolContext) -> str:
    """Reject arguments that do not match the definition; domain rules remain in Java."""
    result = await context.operation("file.read", file_arguments(raw, write=False))
    return str(result["content"])
