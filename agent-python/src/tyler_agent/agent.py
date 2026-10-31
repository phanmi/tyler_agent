"""Bounded model orchestration; Java owns history persistence and application operations."""

import asyncio
import logging

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools import ToolAdapters
from tyler_agent.transcript import (
    AssistantTurn,
    Provider,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    UserTurn,
)

log = logging.getLogger(__name__)


def initial_transcript(payload: Message) -> list[TranscriptEntry]:
    """Saved history is plain text; the current message is the last user turn."""
    transcript: list[TranscriptEntry] = [
        UserTurn(item["content"]) if item["role"] == "user" else AssistantTurn((), item["content"])
        for item in payload["history"]
    ]
    transcript.append(UserTurn(payload["message"]))
    return transcript


async def run_agent(payload: Message, provider: Provider, tools: ToolAdapters) -> str:
    """Run the characterized five rounds, returning only final-response text and refusals."""
    transcript = initial_transcript(payload)
    definitions = [ToolDefinition.from_definition(item) for item in tools.definitions()]
    response = await provider.respond(payload["model"], tuple(transcript), definitions)
    for round_number in range(payload["maxToolRounds"]):
        if not response.calls:
            break
        transcript.append(response)
        for call in response.calls:
            # Yield even with immediately resolved fakes/cache results, so cancellation wins
            # before the next operation or continuation can be started.
            await asyncio.sleep(0)
            result = await tools.execute(call.name, call.arguments)
            transcript.append(ToolResult(call.call_id, result))
        await asyncio.sleep(0)
        response = await provider.respond(payload["model"], tuple(transcript), definitions)
        log.info("Agent tool round completed: round=%d", round_number + 1)
    return response.text
