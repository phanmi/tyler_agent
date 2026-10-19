"""Adapt writeFile arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import Operation
from tyler_agent.tools.parsing import file_arguments


async def execute(raw: str, operation: Operation) -> str:
    """Preserve the existing adapter contract; domain rules remain in Java."""
    result = await operation("file.write", file_arguments(raw, write=True))
    return "Wrote " + str(result["path"])
