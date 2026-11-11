"""Adapt GetUserInfo arguments and results through the Java operation boundary."""

from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.rendering import profile_text


async def execute(raw: str, context: ToolContext) -> str:
    """Ignore model arguments, as this tool declares none; domain rules remain in Java."""
    return profile_text((await context.operation("user_info.read", {}))["profile"])
