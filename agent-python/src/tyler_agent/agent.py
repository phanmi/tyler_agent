"""Bounded model orchestration; Java owns history persistence and application operations."""

import asyncio
import logging
from collections.abc import Sequence

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.contracts import Operation, ToolContext
from tyler_agent.transcript import (
    AssistantTurn,
    Provider,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    UserTurn,
)

log = logging.getLogger(__name__)


def conversation(payload: Message) -> tuple[UserTurn | AssistantTurn, ...]:
    """Saved history is plain text; the current message is the last user turn."""
    turns: list[UserTurn | AssistantTurn] = [
        UserTurn(item["content"]) if item["role"] == "user" else AssistantTurn((), item["content"])
        for item in payload["history"]
    ]
    turns.append(UserTurn(payload["message"]))
    return tuple(turns)


def initial_transcript(payload: Message) -> list[TranscriptEntry]:
    """The run's transcript starts as its conversation and grows with each tool round."""
    return list(conversation(payload))


def tool_context(payload: Message, provider: Provider, operation: Operation) -> ToolContext:
    """Lend tools this run's Java operations, its provider and model, and its conversation."""
    model = payload["model"]

    async def respond(
        transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        return await provider.respond(model, transcript, tools)

    return ToolContext(operation, respond, conversation(payload))


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
