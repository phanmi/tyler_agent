"""Bounded model orchestration; Java owns history persistence and application operations."""

import asyncio
import logging

from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import Provider
from tyler_agent.tools import ToolAdapters

log = logging.getLogger(__name__)


async def run_agent(payload: Message, provider: Provider, tools: ToolAdapters) -> str:
    """Run the characterized five rounds, returning only final-response text and refusals."""
    items = [{"role": item["role"], "content": item["content"]} for item in payload["history"]]
    items.append({"role": "user", "content": payload["message"]})
    response = await provider.respond(payload["model"], items, tools.definitions(), None)
    for round_number in range(payload["maxToolRounds"]):
        if not response.calls:
            break
        outputs: list[Message] = []
        for call in response.calls:
            # Yield even with immediately resolved fakes/cache results, so cancellation wins
            # before the next operation or continuation can be started.
            await asyncio.sleep(0)
            result = await tools.execute(call.name, call.arguments)
            outputs.append(
                {"type": "function_call_output", "call_id": call.call_id, "output": result}
            )
        await asyncio.sleep(0)
        response = await provider.respond(
            payload["model"], outputs, tools.definitions(), response.response_id
        )
        log.info("Agent tool round completed: round=%d", round_number + 1)
    return response.text
