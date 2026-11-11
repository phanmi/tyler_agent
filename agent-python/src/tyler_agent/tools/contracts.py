"""Tool registration values and the per-run capabilities every tool call receives."""

from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass

from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import (
    AssistantTurn,
    ToolDefinition,
    TranscriptEntry,
    UserTurn,
)

type Operation = Callable[[str, Message], Awaitable[Message]]
# One request to this run's provider and model, with a transcript and tools the caller chooses.
type Respond = Callable[
    [Sequence[TranscriptEntry], Sequence[ToolDefinition]], Awaitable[AssistantTurn]
]


@dataclass(frozen=True)
class RunSettings:
    """Behavior switches for one run.

    The run payload carries none of these, so the runtime uses the defaults below. Code that
    drives the agent directly, such as tests, can choose other values.
    """

    # recordFood saves an item only after the run's model accepts it.
    verify_food: bool = True


@dataclass(frozen=True)
class ToolContext:
    """What one run lends its tool calls.

    The runtime builds a context per run and the registry passes it to each handler; the shared
    catalog never holds one, so a tool cannot reach another run's model, conversation or Java
    operations. `conversation` is the run's saved history plus the current user message, as
    text only: no tool results, thinking, or provider-native content.
    """

    operation: Operation
    respond: Respond
    conversation: tuple[UserTurn | AssistantTurn, ...]
    settings: RunSettings


type Handler = Callable[[str, ToolContext], Awaitable[str]]


@dataclass(frozen=True)
class Tool:
    """Pair one advertised definition with its adapter; the registry snapshots the definition."""

    definition: Message
    execute: Handler
