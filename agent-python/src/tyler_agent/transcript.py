"""Provider-neutral transcript, tool and reply types, with the limits every provider applies.

The agent owns the transcript and sends all of it each round, so no provider keeps
conversation state between requests.
"""

import logging
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Protocol

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import MAX_FRAME_BYTES, Message

MAX_CALLS_PER_RESPONSE = 100
MAX_OUTPUT_ITEMS = 1000
PROVIDER_TIMEOUT_SECONDS = 60.0


@dataclass(frozen=True)
class ToolDefinition:
    """A registered tool, independent of any provider's tool format."""

    name: str
    description: str
    parameters: Message

    @classmethod
    def from_definition(cls, definition: Message) -> "ToolDefinition":
        """Read a definition the tool registry has already validated."""
        return cls(definition["name"], definition["description"], definition["parameters"])


@dataclass(frozen=True)
class ToolCall:
    call_id: str
    name: str
    arguments: str


@dataclass(frozen=True)
class UserTurn:
    text: str


@dataclass(frozen=True)
class Usage:
    """Tokens one provider request reported, in the same terms for every provider.

    `input_tokens` counts every input token, including the `cached_input_tokens` the provider
    read from its prompt cache and the `cache_write_tokens` it stored there, which providers
    bill at their own rates. `output_tokens` includes reasoning tokens.
    """

    input_tokens: int
    output_tokens: int
    cached_input_tokens: int = 0
    cache_write_tokens: int = 0


@dataclass(frozen=True)
class AssistantTurn:
    """One model reply. `native` holds the provider's own output for exact replay in this run;
    it is empty for turns loaded from saved history. `usage` is None for those turns, and for
    a reply whose provider reported no token counts."""

    calls: tuple[ToolCall, ...]
    text: str
    native: tuple[Message, ...] = ()
    usage: Usage | None = None


@dataclass(frozen=True)
class ToolResult:
    call_id: str
    output: str


type TranscriptEntry = UserTurn | AssistantTurn | ToolResult


class Provider(Protocol):
    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn: ...


def checked_text(value: object) -> str:
    """Accept only a frame-sized string from provider output."""
    if not isinstance(value, str):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    try:
        if len(value.encode("utf-8")) > MAX_FRAME_BYTES:
            raise AgentFailure("provider", "PROVIDER_FAILURE")
    except UnicodeError as error:
        raise AgentFailure("provider", "PROVIDER_FAILURE") from error
    return value


def checked_calls(calls: list[ToolCall]) -> tuple[ToolCall, ...]:
    if len(calls) > MAX_CALLS_PER_RESPONSE:
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    if any(not call.call_id or not call.name for call in calls):
        raise AgentFailure("provider", "PROVIDER_FAILURE")
    return tuple(calls)


def token_count(value: object) -> int | None:
    """A token count a provider reported; None when it is absent or not a count."""
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


def reported_usage(
    input_tokens: object, output_tokens: object, cached: object, cache_write: object = None
) -> Usage | None:
    """Build a Usage from reported counts. Without both totals there is no usage, so a reply
    is never failed for it; a missing cache count means the provider reported no cache use.
    """
    total, output = token_count(input_tokens), token_count(output_tokens)
    if total is None or output is None:
        return None
    return Usage(total, output, token_count(cached) or 0, token_count(cache_write) or 0)


def nested(value: Message, *path: str) -> object:
    """Read a field under nested objects of provider output; None when any level is absent."""
    current: object = value
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


@contextmanager
def provider_errors(auth: tuple[type[Exception], ...], api: type[Exception]) -> Iterator[None]:
    """Map one SDK's errors to protocol failures; each SDK passes its own exception classes.

    The authentication classes subclass the SDK's base API error, so they are checked first.
    """
    try:
        yield
    except auth as error:
        raise AgentFailure("authentication", "PROVIDER_AUTH") from error
    except api as error:
        raise AgentFailure("provider", "PROVIDER_FAILURE") from error


def suppress_sdk_logs(prefixes: Sequence[str]) -> None:
    # SDK debug output can contain full requests. Suppress third-party payload diagnostics.
    for prefix in prefixes:
        logging.getLogger(prefix).setLevel(logging.CRITICAL + 1)
        for name in list(logging.Logger.manager.loggerDict):
            if name == prefix or name.startswith(prefix + "."):
                logging.getLogger(name).disabled = True
