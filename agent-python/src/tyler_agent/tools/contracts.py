"""Tool registration values and the request-scoped Java operation capability."""

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

from tyler_agent.protocol.protocol import Message

type Operation = Callable[[str, Message], Awaitable[Message]]
type Handler = Callable[[str, Operation], Awaitable[str]]


@dataclass(frozen=True)
class Tool:
    """Pair one advertised definition with its adapter; the registry snapshots the definition."""

    definition: Message
    execute: Handler
