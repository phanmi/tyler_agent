"""Claude Messages API boundary. One cancellable client belongs to one run; no automatic retries."""

import json
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from copy import deepcopy
from typing import cast

from anthropic import APIError, AsyncAnthropic, AuthenticationError, PermissionDeniedError
from anthropic.types.beta import BetaMessageParam, BetaToolParam

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import (
    MAX_OUTPUT_ITEMS,
    PROVIDER_TIMEOUT_SECONDS,
    AssistantTurn,
    Provider,
    ToolCall,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    UserTurn,
    checked_calls,
    checked_text,
    suppress_sdk_logs,
)

CLAUDE_BASE_URL = "https://api.anthropic.com"
# Non-streaming requests stay well inside the SDK's HTTP timeout at this output ceiling.
MAX_TOKENS = 16000
# Server-side fallback re-runs a declined request on Anthropic's recommended model.
FALLBACK_BETA = "server-side-fallback-2026-07-01"
# Returned when the requested model and its fallback both decline.
REFUSAL_REPLY = "Sorry, I can't help with that request."
# Model output replayed within the run. Fallback markers are audit-only and are not echoed.
REPLAYED_BLOCKS = frozenset({"text", "thinking", "redacted_thinking", "tool_use"})
# Blocks from a declining model that must not be echoed once a fallback took over.
DECLINED_BLOCKS = frozenset({"thinking", "redacted_thinking", "tool_use"})
COMPLETE_STOPS = frozenset({"end_turn", "stop_sequence", "tool_use"})


def claude_messages(transcript: Sequence[TranscriptEntry]) -> list[Message]:
    """Render the transcript as Messages API turns; one round's tool results share one turn."""
    messages: list[Message] = []
    for entry in transcript:
        match entry:
            case UserTurn(text) | AssistantTurn(native=(), text=text) if not text:
                # Saved empty replies have no Claude equivalent; adjacent turns are merged.
                continue
            case UserTurn(text):
                messages.append({"role": "user", "content": text})
            case AssistantTurn(native=native) if native:
                messages.append({"role": "assistant", "content": deepcopy(list(native))})
            case AssistantTurn(text=text):
                messages.append({"role": "assistant", "content": text})
            case ToolResult(call_id, output):
                result = {"type": "tool_result", "tool_use_id": call_id, "content": output}
                last = messages[-1] if messages else None
                if (
                    last is not None
                    and last["role"] == "user"
                    and isinstance(last["content"], list)
                ):
                    last["content"].append(result)
                else:
                    messages.append({"role": "user", "content": [result]})
    return messages


def claude_tools(tools: Sequence[ToolDefinition]) -> list[Message]:
    return [
        {
            "name": tool.name,
            "description": tool.description,
            "input_schema": deepcopy(tool.parameters),
            "strict": True,
        }
        for tool in tools
    ]


def parse_message(value: Message) -> AssistantTurn:
    """Check the stop reason before content; keep text and calls after any fallback boundary."""
    stop = value.get("stop_reason")
    if stop == "refusal":
        return AssistantTurn((), REFUSAL_REPLY)
    if stop not in COMPLETE_STOPS:
        # max_tokens and the context-window stop can cut a tool call short; never run it.
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    content = value.get("content")
    if not isinstance(content, list) or len(content) > MAX_OUTPUT_ITEMS:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    if not all(isinstance(block, dict) for block in content):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    kinds = [block.get("type") for block in content]
    boundary = max((index for index, kind in enumerate(kinds) if kind == "fallback"), default=-1)
    calls: list[ToolCall] = []
    text: list[str] = []
    native: list[Message] = []
    for index, block in enumerate(content):
        kind = block.get("type")
        if kind not in REPLAYED_BLOCKS or (index < boundary and kind in DECLINED_BLOCKS):
            continue
        if kind == "text":
            text.append(checked_text(block.get("text")))
        elif kind == "tool_use":
            arguments = block.get("input")
            if not isinstance(arguments, dict):
                raise AgentFailure("provider", "PROVIDER_FAILURE")
            calls.append(
                ToolCall(
                    checked_text(block.get("id")),
                    checked_text(block.get("name")),
                    checked_text(json.dumps(arguments, ensure_ascii=False)),
                )
            )
        native.append(block)
    return AssistantTurn(checked_calls(calls), checked_text("".join(text)), tuple(native))


class ClaudeProvider:
    """Adaptive thinking at the model's default effort, with server-side refusal fallback."""

    def __init__(self, client: AsyncAnthropic) -> None:
        self._client = client

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        try:
            # These casts bridge validated application dictionaries to SDK TypedDict unions.
            message = await self._client.beta.messages.create(
                model=model,
                max_tokens=MAX_TOKENS,
                messages=cast(list[BetaMessageParam], claude_messages(transcript)),
                tools=cast(list[BetaToolParam], claude_tools(tools)),
                thinking={"type": "adaptive"},
                fallbacks="default",
                betas=[FALLBACK_BETA],
            )
            # Only fields the server sent, so replayed blocks are valid request input.
            return parse_message(message.model_dump(mode="json", exclude_unset=True))
        except (AuthenticationError, PermissionDeniedError) as error:
            raise AgentFailure("authentication", "PROVIDER_AUTH") from error
        except APIError as error:
            raise AgentFailure("provider", "PROVIDER_FAILURE") from error


@asynccontextmanager
async def open_claude(payload: Message) -> AsyncIterator[Provider]:
    suppress_sdk_logs(("anthropic", "httpx", "httpx2", "httpcore"))
    # An explicit key, URL and telemetry setting keep environment and on-disk profiles out.
    async with AsyncAnthropic(
        api_key=payload["apiKey"],
        base_url=CLAUDE_BASE_URL,
        max_retries=0,
        timeout=min(PROVIDER_TIMEOUT_SECONDS, payload["timeoutMs"] / 1000),
        open_telemetry={"traces": False, "propagation": False},
    ) as client:
        yield ClaudeProvider(client)
