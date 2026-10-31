"""Provider SDK boundary for OpenAI, and the run's provider choice.

One cancellable client belongs to one run; no automatic retries.
"""

from collections.abc import AsyncIterator, Callable, Sequence
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from copy import deepcopy
from typing import cast

from openai import APIError, AsyncOpenAI, AuthenticationError, PermissionDeniedError
from openai.types.responses import ResponseInputParam, ToolParam

from tyler_agent.claude_provider import open_claude
from tyler_agent.deepseek_provider import open_deepseek
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

# Re-exported so callers keep one import location for the provider boundary.
__all__ = [
    "AssistantTurn",
    "Provider",
    "ResponsesProvider",
    "ToolCall",
    "ToolDefinition",
    "ToolResult",
    "TranscriptEntry",
    "UserTurn",
    "open_provider",
    "parse_response",
    "responses_input",
    "responses_tools",
]

OPENAI_BASE_URL = "https://api.openai.com/v1"
# Output item kinds replayed to OpenAI; reasoning must stay paired with its function calls.
OPENAI_REPLAYED_ITEMS = frozenset({"reasoning", "message", "function_call"})


def parse_response(value: Message) -> AssistantTurn:
    """Preserve ordered text/refusals and function calls; ignore other provider item kinds.

    A response that did not complete (cut off at the output limit, filtered, or failed) is a
    provider failure, so partial text is never answered and partial tool calls never run.
    """
    response_id = checked_text(value.get("id"))
    output = value.get("output")
    if not response_id or not isinstance(output, list) or len(output) > MAX_OUTPUT_ITEMS:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    if value.get("status") != "completed":
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    calls: list[ToolCall] = []
    text: list[str] = []
    native: list[Message] = []
    for item in output:
        if not isinstance(item, dict):
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        if item.get("type") == "function_call":
            calls.append(
                ToolCall(
                    checked_text(item.get("call_id")),
                    checked_text(item.get("name")),
                    checked_text(item.get("arguments")),
                )
            )
        elif item.get("type") == "message":
            text.extend(_message_text(item))
        if item.get("type") in OPENAI_REPLAYED_ITEMS:
            native.append(item)
    return AssistantTurn(checked_calls(calls), checked_text("".join(text)), tuple(native))


def _message_text(item: Message) -> list[str]:
    content = item.get("content")
    if not isinstance(content, list):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    parts: list[str] = []
    for part in content:
        if not isinstance(part, dict):
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        kind = part.get("type")
        if not isinstance(kind, str):
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        field = {"output_text": "text", "refusal": "refusal"}.get(kind)
        if field is not None:
            parts.append(checked_text(part.get(field)))
    return parts


def responses_input(transcript: Sequence[TranscriptEntry]) -> list[Message]:
    """Render the transcript as Responses input items, replaying this run's output exactly."""
    items: list[Message] = []
    for entry in transcript:
        match entry:
            case UserTurn(text):
                items.append({"role": "user", "content": text})
            case AssistantTurn(native=native) if native:
                items.extend(deepcopy(list(native)))
            case AssistantTurn(text=text):
                items.append({"role": "assistant", "content": text})
            case ToolResult(call_id, output):
                items.append({"type": "function_call_output", "call_id": call_id, "output": output})
    return items


def responses_tools(tools: Sequence[ToolDefinition]) -> list[Message]:
    return [
        {
            "type": "function",
            "name": tool.name,
            "description": tool.description,
            "parameters": deepcopy(tool.parameters),
            "strict": True,
        }
        for tool in tools
    ]


class ResponsesProvider:
    """OpenAI Responses API without server-side storage; reasoning returns encrypted for replay."""

    def __init__(self, client: AsyncOpenAI) -> None:
        self._client = client

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        try:
            # These casts bridge validated application dictionaries to SDK TypedDict unions.
            response = await self._client.responses.create(
                model=model,
                input=cast(ResponseInputParam, responses_input(transcript)),
                tools=cast(list[ToolParam], responses_tools(tools)),
                store=False,
                include=["reasoning.encrypted_content"],
            )
            # Only fields the server sent, so replayed items are valid request input.
            return parse_response(response.model_dump(mode="json", exclude_unset=True))
        except (AuthenticationError, PermissionDeniedError) as error:
            raise AgentFailure("authentication", "PROVIDER_AUTH") from error
        except APIError as error:
            raise AgentFailure("provider", "PROVIDER_FAILURE") from error


@asynccontextmanager
async def _open_openai(payload: Message) -> AsyncIterator[Provider]:
    suppress_sdk_logs(("openai", "httpx", "httpx2", "httpcore"))
    async with AsyncOpenAI(
        api_key=payload["apiKey"],
        base_url=OPENAI_BASE_URL,
        max_retries=0,
        timeout=min(PROVIDER_TIMEOUT_SECONDS, payload["timeoutMs"] / 1000),
    ) as client:
        yield ResponsesProvider(client)


OPENERS: dict[str, Callable[[Message], AbstractAsyncContextManager[Provider]]] = {
    "openai": _open_openai,
    "anthropic": open_claude,
    "deepseek": open_deepseek,
}


@asynccontextmanager
async def open_provider(payload: Message) -> AsyncIterator[Provider]:
    """No credential reads at readiness; the Java snapshot is used only for this run."""
    opener = OPENERS.get(payload["provider"])
    if opener is None:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    async with opener(payload) as provider:
        yield provider
