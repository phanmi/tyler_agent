"""Adapt getCurrentTime arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import Operation, ToolContext


async def current_time(operation: Operation) -> str:
    """Java's server-local date and time text; Python never substitutes its own clock."""
    return str((await operation("time.current", {}))["text"])


async def execute(raw: str, context: ToolContext) -> str:
    """Ignore model arguments, as this tool declares none; domain rules remain in Java."""
    return await current_time(context.operation)
