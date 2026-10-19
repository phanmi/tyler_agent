"""Adapt GetUserInfo arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import Operation
from tyler_agent.tools.rendering import profile_text


async def execute(raw: str, operation: Operation) -> str:
    """Preserve the existing adapter contract; domain rules remain in Java."""
    return profile_text((await operation("user_info.read", {}))["profile"])
