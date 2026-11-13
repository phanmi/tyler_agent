"""Run one case as one chat through the real agent, provider and tools, and record what it did.

Only Java is replaced. The provider is wrapped so every model request is counted, including
the verification requests recordFood makes, without any tool knowing it is measured.
"""

import asyncio
import json
import time
from collections.abc import Callable, Mapping, Sequence
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass, field

from evals.cases import Case
from evals.java import FakeJava, UnexpectedOperation
from tyler_agent.agent import run_agent, tool_context
from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import open_provider
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.builtin.food_record.verification import NOT_RECORDED
from tyler_agent.tools.contracts import RunSettings, ToolContext
from tyler_agent.tools.discovery import discover_tools
from tyler_agent.tools.registry import ToolCatalog
from tyler_agent.transcript import AssistantTurn, Provider, ToolDefinition, TranscriptEntry, Usage

type ProviderFactory = Callable[[Message], AbstractAsyncContextManager[Provider]]

RECORD_FOOD = "recordFood"
# The values Java sends with every run; the protocol fixes the round limit at five.
MAX_TOOL_ROUNDS = 5
CHAT_TIMEOUT_MS = 300_000
OPERATION_TIMEOUT_MS = 60_000
# How a failed chat is described in its result. An agent failure is "category/code".
TIMEOUT_ERROR = "timeout"
UNEXPECTED_OPERATION_ERROR = "unexpected operation: {name}"
INTERNAL_ERROR = "internal: {exception}"


@dataclass(frozen=True)
class Target:
    """The provider and model a chat runs on, as the run payload names them."""

    provider: str
    model: str


@dataclass(frozen=True)
class Chat:
    """One planned chat: a case on a target, with verification on or off, and which repeat."""

    case: Case
    target: Target
    verify: bool
    repeat: int

    @property
    def mode(self) -> str:
        """Food verification as progress lines and the report name it."""
        return "on" if self.verify else "off"


@dataclass(frozen=True)
class ModelCall:
    """One model request: what the provider reported for it and how long it took."""

    usage: Usage | None
    seconds: float


@dataclass(frozen=True)
class FoodCheck:
    """One recordFood call: the model requests made inside it, and whether it was refused.

    Verification makes one request for an item it accepts straight away, so a saved item with
    more than one request was rejected at least once first.
    """

    model_calls: int
    refused: bool


@dataclass(frozen=True)
class ChatResult:
    """What one chat did. `error` is None for a chat that finished; a failed chat keeps what
    it had recorded up to the failure, and the report leaves it out of every measurement."""

    chat: Chat
    error: str | None
    saved: tuple[Message, ...]
    checks: tuple[FoodCheck, ...]
    calls: tuple[ModelCall, ...]
    seconds: float


@dataclass(frozen=True)
class Session:
    """What every chat of a harness run shares: the API key for each provider, how a provider
    is opened, the clock, and the tool catalog. Keys stay here and never enter a result."""

    keys: Mapping[str, str]
    opener: ProviderFactory = open_provider
    clock: Callable[[], float] = time.perf_counter
    catalog: ToolCatalog = field(default_factory=lambda: ToolCatalog(discover_tools()))


class MeteredProvider:
    """Wraps a chat's provider and notes each request's usage and duration.

    `calls` starts empty and grows by one for each request that returns; a request that
    raises adds nothing. One instance belongs to one chat.
    """

    def __init__(self, inner: Provider, clock: Callable[[], float]) -> None:
        self._inner = inner
        self._clock = clock
        self.calls: list[ModelCall] = []

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        started = self._clock()
        turn = await self._inner.respond(model, transcript, tools)
        self.calls.append(ModelCall(turn.usage, self._clock() - started))
        return turn


class RecordedTools(ToolAdapters):
    """The chat's real tools, noting for each recordFood call how many model requests ran
    inside it and whether verification refused it.

    `checks` starts empty and grows by one for each recordFood call the model makes,
    including calls whose arguments were rejected. One instance belongs to one chat.
    """

    def __init__(self, context: ToolContext, catalog: ToolCatalog, meter: MeteredProvider) -> None:
        super().__init__(context, catalog)
        self._meter = meter
        self.checks: list[FoodCheck] = []

    async def execute(self, name: str, raw: str) -> str:
        before = len(self._meter.calls)
        result = await super().execute(name, raw)
        if name == RECORD_FOOD:
            self.checks.append(FoodCheck(len(self._meter.calls) - before, was_refused(result)))
        return result


def was_refused(result: str) -> bool:
    """True for the result recordFood returns when verification refused the item."""
    try:
        value = json.loads(result)
    except ValueError:
        return False
    return isinstance(value, dict) and value.get("error") == NOT_RECORDED


def run_payload(chat: Chat, api_key: str) -> Message:
    """The run payload Java would send for this chat."""
    return {
        "message": chat.case.message,
        "history": [dict(turn) for turn in chat.case.history],
        "provider": chat.target.provider,
        "model": chat.target.model,
        "apiKey": api_key,
        "maxToolRounds": MAX_TOOL_ROUNDS,
        "timeoutMs": CHAT_TIMEOUT_MS,
        "operationTimeoutMs": OPERATION_TIMEOUT_MS,
    }


async def run_chat(chat: Chat, session: Session) -> ChatResult:
    """Run one chat to its end. A chat that fails, for example on a rate limit, comes back
    with `error` set and never raises, so one failure cannot lose the rest of a paid run."""
    payload = run_payload(chat, session.keys[chat.target.provider])
    java = FakeJava(chat.case.now)
    meter: MeteredProvider | None = None
    tools: RecordedTools | None = None
    error: str | None = None
    started = session.clock()
    try:
        async with asyncio.timeout(CHAT_TIMEOUT_MS / 1000):
            async with session.opener(payload) as provider:
                meter = MeteredProvider(provider, session.clock)
                settings = RunSettings(verify_food=chat.verify)
                context = tool_context(payload, meter, java.operation, settings)
                tools = RecordedTools(context, session.catalog, meter)
                await run_agent(payload, meter, tools)
    except AgentFailure as failure:
        error = f"{failure.category}/{failure.code}"
    except UnexpectedOperation as unexpected:
        error = UNEXPECTED_OPERATION_ERROR.format(name=unexpected.name)
    except TimeoutError:
        error = TIMEOUT_ERROR
    except Exception as unknown:
        # Deliberate boundary: a bug in one chat must not discard the chats already paid
        # for. Only the exception type is kept, because its text could quote a request.
        error = INTERNAL_ERROR.format(exception=type(unknown).__name__)
    return ChatResult(
        chat,
        error,
        tuple(java.saved),
        tuple(tools.checks) if tools else (),
        tuple(meter.calls) if meter else (),
        session.clock() - started,
    )
