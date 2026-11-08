"""Adapt getCurrentTime arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import Operation


async def execute(raw: str, operation: Operation) -> str:
    """Ignore model arguments, as this tool declares none; domain rules remain in Java."""
    return str((await operation("time.current", {}))["text"])
