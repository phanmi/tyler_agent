"""Adapt getCurrentTime arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import Operation


async def execute(raw: str, operation: Operation) -> str:
    """Preserve the existing adapter contract; domain rules remain in Java."""
    return str((await operation("time.current", {}))["text"])
