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
    # recordFood's content check judges each food's nutrition values, and has the ones it
    # rejects written again. The name and count check and the date check have no switch.
    check_nutrition: bool = True


@dataclass(frozen=True)
class CompletedCall:
    """One tool call this run has finished: what the model asked for, and the result it was
    given. The result is whatever the tool returned, so treat it as untrusted text."""

    name: str
    arguments: str
    result: str


@dataclass(frozen=True)
class ToolContext:
    """What one run lends its tool calls.

    The runtime builds a context per run and the registry passes it to each handler; the shared
    catalog never holds one, so a tool cannot reach another run's model, conversation or Java
    operations. `conversation` is the run's saved history plus the current user message, as
    text only: no tool results, thinking, or provider-native content.

    `earlier_calls` is every tool call the run finished before this one, oldest first, whatever
    the tool. The registry fills it in for each call, so a tool needs no wiring of its own to
    see what the chat model was given, such as a file it read.
    """

    operation: Operation
    respond: Respond
    conversation: tuple[UserTurn | AssistantTurn, ...]
    settings: RunSettings
    earlier_calls: tuple[CompletedCall, ...] = ()


type Handler = Callable[[str, ToolContext], Awaitable[str]]


@dataclass(frozen=True)
class Tool:
    """Pair one advertised definition with its adapter; the registry snapshots the definition."""

    definition: Message
    execute: Handler
