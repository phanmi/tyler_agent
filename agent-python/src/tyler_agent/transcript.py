"""Provider-neutral transcript, tool and reply types, with the limits every provider applies.

The agent owns the transcript and sends all of it each round, so no provider keeps
conversation state between requests.
"""

import logging
from collections.abc import Sequence
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
class AssistantTurn:
    """One model reply. `native` holds the provider's own output for exact replay in this run;
    it is empty for turns loaded from saved history."""

    calls: tuple[ToolCall, ...]
    text: str
    native: tuple[Message, ...] = ()


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


def suppress_sdk_logs(prefixes: Sequence[str]) -> None:
    # SDK debug output can contain full requests. Suppress third-party payload diagnostics.
    for prefix in prefixes:
        logging.getLogger(prefix).setLevel(logging.CRITICAL + 1)
        for name in list(logging.Logger.manager.loggerDict):
            if name == prefix or name.startswith(prefix + "."):
                logging.getLogger(name).disabled = True
