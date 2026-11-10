"""Adapt getCurrentTime arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import ToolContext


async def execute(raw: str, context: ToolContext) -> str:
    """Ignore model arguments, as this tool declares none; domain rules remain in Java."""
    return str((await context.operation("time.current", {}))["text"])
