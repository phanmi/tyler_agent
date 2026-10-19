"""Responses SDK boundary. One cancellable client belongs to one run; no automatic retries."""

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Protocol, cast

from openai import APIError, AsyncOpenAI, AuthenticationError, PermissionDeniedError
from openai.types.responses import ResponseInputParam, ToolParam

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import MAX_FRAME_BYTES, Message

MAX_CALLS_PER_RESPONSE = 100
MAX_OUTPUT_ITEMS = 1000
PROVIDER_TIMEOUT_SECONDS = 60.0
OPENAI_BASE_URL = "https://api.openai.com/v1"


@dataclass(frozen=True)
class ToolCall:
    call_id: str
    name: str
    arguments: str


@dataclass(frozen=True)
class ModelResponse:
    response_id: str
    calls: tuple[ToolCall, ...]
    text: str


class Provider(Protocol):
    async def respond(
        self, model: str, items: list[Message], tools: list[Message], previous_id: str | None
    ) -> ModelResponse: ...


def _text(value: object) -> str:
    if not isinstance(value, str):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    try:
        if len(value.encode("utf-8")) > MAX_FRAME_BYTES:
            raise AgentFailure("provider", "PROVIDER_FAILURE")
    except UnicodeError as error:
        raise AgentFailure("provider", "PROVIDER_FAILURE") from error
    return value


def parse_response(value: Message) -> ModelResponse:
    """Preserve ordered text/refusals and function calls; ignore other provider item kinds."""
    response_id = _text(value.get("id"))
    output = value.get("output")
    if not response_id or not isinstance(output, list) or len(output) > MAX_OUTPUT_ITEMS:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    calls: list[ToolCall] = []
    text: list[str] = []
    for item in output:
        if not isinstance(item, dict):
            raise AgentFailure("provider", "PROVIDER_FAILURE")
        if item.get("type") == "function_call":
            calls.append(
                ToolCall(
                    _text(item.get("call_id")),
                    _text(item.get("name")),
                    _text(item.get("arguments")),
                )
            )
        elif item.get("type") == "message":
            text.extend(_message_text(item))
    if len(calls) > MAX_CALLS_PER_RESPONSE:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    if any(not call.call_id or not call.name for call in calls):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    return ModelResponse(response_id, tuple(calls), _text("".join(text)))


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
            parts.append(_text(part.get(field)))
    return parts


class ResponsesProvider:
    def __init__(self, client: AsyncOpenAI) -> None:
        self._client = client

    async def respond(
        self, model: str, items: list[Message], tools: list[Message], previous_id: str | None
    ) -> ModelResponse:
        try:
            # These casts bridge validated application dictionaries to SDK TypedDict unions.
            response = await self._client.responses.create(
                model=model,
                input=cast(ResponseInputParam, items),
                tools=cast(list[ToolParam], tools),
                previous_response_id=previous_id,
            )
            return parse_response(response.model_dump(mode="json"))
        except (AuthenticationError, PermissionDeniedError) as error:
            raise AgentFailure("authentication", "PROVIDER_AUTH") from error
        except APIError as error:
            raise AgentFailure("provider", "PROVIDER_FAILURE") from error


@asynccontextmanager
async def open_provider(payload: Message) -> AsyncIterator[Provider]:
    """No credential reads at readiness; the Java snapshot is used only for this run."""
    # SDK debug output can contain full requests. Suppress third-party payload diagnostics.
    for prefix in ("openai", "httpx", "httpx2", "httpcore"):
        logging.getLogger(prefix).setLevel(logging.CRITICAL + 1)
        for name in list(logging.Logger.manager.loggerDict):
            if name == prefix or name.startswith(prefix + "."):
                logging.getLogger(name).disabled = True
    async with AsyncOpenAI(
        api_key=payload["apiKey"],
        base_url=OPENAI_BASE_URL,
        max_retries=0,
        timeout=min(PROVIDER_TIMEOUT_SECONDS, payload["timeoutMs"] / 1000),
    ) as client:
        yield ResponsesProvider(client)
