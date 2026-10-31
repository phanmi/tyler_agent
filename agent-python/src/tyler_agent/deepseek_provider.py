"""DeepSeek Chat Completions boundary. One cancellable client belongs to one run; no retries."""

from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from copy import deepcopy
from typing import cast

from openai import (
    APIError,
    AsyncOpenAI,
    AuthenticationError,
    PermissionDeniedError,
    omit,
)
from openai.types.chat import ChatCompletionMessageParam, ChatCompletionToolUnionParam

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import (
    MAX_CALLS_PER_RESPONSE,
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

DEEPSEEK_BASE_URL = "https://api.deepseek.com"
# With tools present, thinking mode requires every earlier assistant turn to carry its
# reasoning_content. Saved history is plain text, so thinking stays off.
THINKING_DISABLED = {"thinking": {"type": "disabled"}}
# `length`, `content_filter` and the server-side stops can cut a tool call short.
COMPLETE_FINISHES = frozenset({"stop", "tool_calls"})


def chat_messages(transcript: Sequence[TranscriptEntry]) -> list[Message]:
    """Render the transcript as chat messages: assistant tool_calls, then one tool message each."""
    messages: list[Message] = []
    for entry in transcript:
        match entry:
            case UserTurn(text):
                messages.append({"role": "user", "content": text})
            case AssistantTurn(native=native) if native:
                messages.extend(deepcopy(list(native)))
            case AssistantTurn(text=text):
                messages.append({"role": "assistant", "content": text})
            case ToolResult(call_id, output):
                messages.append({"role": "tool", "tool_call_id": call_id, "content": output})
    return messages


def chat_tools(tools: Sequence[ToolDefinition]) -> list[Message]:
    # Strict schemas exist only on DeepSeek's beta endpoint; the worker validates arguments.
    return [
        {
            "type": "function",
            "function": {
                "name": tool.name,
                "description": tool.description,
                "parameters": deepcopy(tool.parameters),
            },
        }
        for tool in tools
    ]


def parse_completion(value: Message) -> AssistantTurn:
    """Check the finish reason before content; replay only the validated assistant message."""
    choices = value.get("choices")
    if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    choice = choices[0]
    if choice.get("finish_reason") not in COMPLETE_FINISHES:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    message = choice.get("message")
    if not isinstance(message, dict):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    content = message.get("content")
    text = "" if content is None else checked_text(content)
    raw_calls = message.get("tool_calls") or []
    if not isinstance(raw_calls, list) or len(raw_calls) > MAX_CALLS_PER_RESPONSE:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    calls: list[ToolCall] = []
    for item in raw_calls:
        function = item.get("function") if isinstance(item, dict) else None
        if not isinstance(function, dict) or item.get("type") != "function":
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        calls.append(
            ToolCall(
                checked_text(item.get("id")),
                checked_text(function.get("name")),
                checked_text(function.get("arguments")),
            )
        )
    native: Message = {"role": "assistant", "content": content}
    if calls:
        native["tool_calls"] = [
            {
                "id": call.call_id,
                "type": "function",
                "function": {"name": call.name, "arguments": call.arguments},
            }
            for call in calls
        ]
    return AssistantTurn(checked_calls(calls), text, (native,))


class DeepSeekProvider:
    """DeepSeek through its OpenAI-compatible Chat Completions API, with thinking disabled."""

    def __init__(self, client: AsyncOpenAI) -> None:
        self._client = client

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        try:
            # These casts bridge validated application dictionaries to SDK TypedDict unions.
            completion = await self._client.chat.completions.create(
                model=model,
                messages=cast(list[ChatCompletionMessageParam], chat_messages(transcript)),
                tools=cast(list[ChatCompletionToolUnionParam], chat_tools(tools)) or omit,
                extra_body=THINKING_DISABLED,
            )
            return parse_completion(completion.model_dump(mode="json", exclude_unset=True))
        except (AuthenticationError, PermissionDeniedError) as error:
            raise AgentFailure("authentication", "PROVIDER_AUTH") from error
        except APIError as error:
            raise AgentFailure("provider", "PROVIDER_FAILURE") from error


@asynccontextmanager
async def open_deepseek(payload: Message) -> AsyncIterator[Provider]:
    suppress_sdk_logs(("openai", "httpx", "httpx2", "httpcore"))
    async with AsyncOpenAI(
        api_key=payload["apiKey"],
        base_url=DEEPSEEK_BASE_URL,
        max_retries=0,
        timeout=min(PROVIDER_TIMEOUT_SECONDS, payload["timeoutMs"] / 1000),
    ) as client:
        yield DeepSeekProvider(client)
