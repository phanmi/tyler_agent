"""Claude Messages API boundary. One cancellable client belongs to one run; no automatic retries."""

import json
import re
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from copy import deepcopy
from typing import Any, cast

from anthropic import APIError, AsyncAnthropic, AuthenticationError, PermissionDeniedError
from anthropic.types.beta import BetaMessageParam, BetaToolParam

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import (
    MAX_OUTPUT_ITEMS,
    AssistantTurn,
    Provider,
    ToolCall,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    UserTurn,
    checked_calls,
    checked_text,
    provider_errors,
    suppress_sdk_logs,
)

CLAUDE_BASE_URL = "https://api.anthropic.com"
# Requests do not stream, so a long thinking reply at this ceiling can take minutes; the client
# timeout is the whole run budget rather than the shorter cap used for the other providers.
MAX_TOKENS = 16000
# Adaptive thinking and server-side fallback exist from Claude 4.6 on. Custom IDs outside these
# families, such as claude-sonnet-4-5-20250929, get a plain request so they still work.
CLAUDE_FAMILY_ID = re.compile(r"claude-[a-z]+-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?")
ADAPTIVE_FROM = (4, 6)
# Server-side fallback re-runs a declined request on Anthropic's recommended model.
FALLBACK_BETA = "server-side-fallback-2026-07-01"
# Returned when the requested model and its fallback both decline.
REFUSAL_REPLY = "Sorry, I can't help with that request."
# Model output replayed within the run. A fallback marker is replayed in place: the API uses its
# position to validate the thinking blocks around it.
REPLAYED_BLOCKS = frozenset({"text", "thinking", "redacted_thinking", "tool_use", "fallback"})
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


def supports_adaptive_features(model: str) -> bool:
    """True for Claude 4.6 and later IDs, such as claude-sonnet-5-5 or claude-opus-4-6-20260101."""
    match = CLAUDE_FAMILY_ID.fullmatch(model)
    if match is None:
        return False
    return (int(match[1]), int(match[2] or 0)) >= ADAPTIVE_FROM


def request_features(model: str) -> dict[str, Any]:
    if not supports_adaptive_features(model):
        return {}
    return {"thinking": {"type": "adaptive"}, "fallbacks": "default", "betas": [FALLBACK_BETA]}


class ClaudeProvider:
    """Adaptive thinking at the model's default effort, with server-side refusal fallback,
    for the models that support them."""

    def __init__(self, client: AsyncAnthropic) -> None:
        self._client = client

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        with provider_errors((AuthenticationError, PermissionDeniedError), APIError):
            # These casts bridge validated application dictionaries to SDK TypedDict unions.
            message = await self._client.beta.messages.create(
                model=model,
                max_tokens=MAX_TOKENS,
                messages=cast(list[BetaMessageParam], claude_messages(transcript)),
                tools=cast(list[BetaToolParam], claude_tools(tools)),
                **request_features(model),
            )
            # Only fields the server sent, under their wire names (a fallback block's "from"), so
            # replayed blocks are valid request input.
            return parse_message(message.model_dump(mode="json", by_alias=True, exclude_unset=True))


@asynccontextmanager
async def open_claude(payload: Message) -> AsyncIterator[Provider]:
    suppress_sdk_logs(("anthropic", "httpx", "httpx2", "httpcore"))
    # An explicit key, URL and telemetry setting keep environment and on-disk profiles out.
    async with AsyncAnthropic(
        api_key=payload["apiKey"],
        base_url=CLAUDE_BASE_URL,
        max_retries=0,
        timeout=payload["timeoutMs"] / 1000,
        open_telemetry={"traces": False, "propagation": False},
    ) as client:
        yield ClaudeProvider(client)
