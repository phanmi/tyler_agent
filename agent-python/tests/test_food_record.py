"""recordFood with its checks, through the real agent loop, each provider class and the
runtime: what is saved, in what order, and what a failure leaves behind (#113)."""

import asyncio
import json
from collections.abc import AsyncIterator, Callable, Sequence
from contextlib import AbstractAsyncContextManager, asynccontextmanager

import httpx2
import pytest
from anthropic import AsyncAnthropic
from openai import AsyncOpenAI
from support.agent_fakes import DEFAULT_SETTINGS, FakeProvider, payload
from support.food_fakes import TODAY, Flag, Java, answer, flags, food_raw, foods_raw
from support.http_server import Server
from support.persistence_worker import ALL_CLEAR, all_clear

from tyler_agent.agent import run_agent, tool_context
from tyler_agent.claude_provider import ClaudeProvider
from tyler_agent.deepseek_provider import DEEPSEEK_BASE_URL, DeepSeekProvider
from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import Provider, ResponsesProvider
from tyler_agent.runtime import WorkerRuntime
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.builtin.food_record.adapter import NOT_RECORDED
from tyler_agent.tools.builtin.food_record.asking import REQUIRED_ATTEMPTS
from tyler_agent.tools.builtin.food_record.content_check import VERIFY_FOOD_NUTRITION
from tyler_agent.tools.builtin.food_record.correction import CORRECT_FOOD
from tyler_agent.tools.builtin.food_record.date_check import CORRECT_FOOD_DATES, VERIFY_FOOD_DATES
from tyler_agent.tools.builtin.food_record.name_check import VERIFY_FOOD_LIST
from tyler_agent.tools.contracts import RunSettings
from tyler_agent.tools.parsing import food_arguments
from tyler_agent.tools.rendering import pretty
from tyler_agent.transcript import (
    AssistantTurn,
    ToolCall,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    UserTurn,
)

LIST, DATES, NUTRITION = VERIFY_FOOD_LIST.name, VERIFY_FOOD_DATES.name, VERIFY_FOOD_NUTRITION.name
CHICKEN = food_raw()
RICE = food_raw(foodName="Cooked rice", amount=1, unit="cup", calories=205)
OATS = food_raw(foodName="Oats", amount=50, calories=190)
DINNER = food_raw(foodName="Dinner", amount=1, unit="meal", calories=535)
# An uncommon unit reaches Java as the model gave it, never normalized on the way (#104).
SCOOP = food_raw(foodName="Whey protein powder", amount=1, unit="scoop", calories=120)
LEAN = dict(protein=60, carbs=0, fat=6, fiber=0)
DONE = AssistantTurn((), "Recorded.")
UNUSABLE_TEXT = AssistantTurn((), "It all looks right.")
FAILURE = AgentFailure("provider", "PROVIDER_FAILURE")


def chat(*foods: str, call_id: str = "food") -> ToolCall:
    """The chat model's recordFood call."""
    return ToolCall(call_id, "recordFood", foods_raw(*foods))


def asked(*calls: ToolCall) -> AssistantTurn:
    return AssistantTurn(calls, "")


def clear(check: str) -> AssistantTurn:
    """The answer of a model that finds nothing wrong, to this check."""
    return asked(ToolCall(check, check, ALL_CLEAR[check]))


def listed(missing: Sequence[str] = (), incorrect: Sequence[Flag] = ()) -> AssistantTurn:
    value = dict(missing=list(missing), incorrect=flags(incorrect), wrongAmount=[])
    return answer(LIST, json.dumps(value))


class Recording(FakeProvider):
    """A scripted provider that also notes how many foods Java had saved at each request."""

    def __init__(self, java: Java, responses: list[AssistantTurn | Exception]) -> None:
        super().__init__(responses)
        self.java = java
        self.saved_at_request: list[int] = []

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        self.saved_at_request.append(len(self.java.saved()))
        return await super().respond(model, transcript, tools)

    def checks(self) -> list[str]:
        """The tool each request inside recordFood offered, in order."""
        offered = [[tool.name for tool in request["tools"]] for request in self.requests]
        return [names[0] for names in offered if len(names) == 1]

    def prompt(self, index: int) -> str:
        """The text of the single user turn a check or correction request carries."""
        (turn,) = self.requests[index]["transcript"]
        assert isinstance(turn, UserTurn)
        return turn.text

    def tool_result(self) -> str:
        """What the chat model was given back for its last tool call."""
        result = self.requests[-1]["transcript"][-1]
        assert isinstance(result, ToolResult)
        return result.output


def run(
    replies: list[AssistantTurn | Exception],
    java: Java | None = None,
    settings: RunSettings = DEFAULT_SETTINGS,
) -> tuple[str, Java, Recording]:
    """Run one chat through the real agent loop, tool registry and recordFood."""
    java = java or Java()
    provider = Recording(java, replies)
    data = payload()

    async def scenario() -> str:
        context = tool_context(data, provider, java, settings)
        return await run_agent(data, provider, ToolAdapters(context))

    return asyncio.run(scenario()), java, provider


def test_the_checks_run_in_order_and_nothing_is_saved_before_the_last_one() -> None:
    """A whole-meal record is replaced by its food, an invented date is corrected and an
    implausible value is rewritten, each by its own check, before Java is sent any food."""
    whole = "It stands for the whole meal."
    invented = food_raw(foodName="Oats", amount=50, calories=190, date="2026-09-07")
    replies: list[AssistantTurn | Exception] = [
        asked(chat(DINNER, invented)),
        listed(missing=["200 g of chicken breast"], incorrect=[(1, whole)]),
        answer(CORRECT_FOOD, CHICKEN),
        answer(DATES, json.dumps({"wrong": flags([(1, "No day was given, so it is today.")])})),
        answer(CORRECT_FOOD_DATES.name, '{"dates": [{"number": 1, "date": "2026-11-10"}]}'),
        clear(NUTRITION),
        answer(NUTRITION, '{"valid": false, "reason": "Too much fat for chicken breast."}'),
        answer(CORRECT_FOOD, food_raw(macros=LEAN)),
        DONE,
    ]
    reply, java, provider = run(replies)

    assert reply == "Recorded."
    assert provider.checks() == [
        LIST,
        CORRECT_FOOD,
        DATES,
        CORRECT_FOOD_DATES.name,
        NUTRITION,
        NUTRITION,
        CORRECT_FOOD,
    ]
    saved = [food_arguments(OATS), food_arguments(food_raw(macros=LEAN))]
    assert java.saved() == saved
    # Every request inside recordFood came before the first save.
    assert provider.saved_at_request == [0] * 8 + [2]
    expected = {
        "recorded": [food["food"] for food in saved],
        "notRecorded": [{"foodName": "Dinner", "reason": whole}],
        "note": NOT_RECORDED,
    }
    assert provider.tool_result() == pretty(expected)


def test_a_food_from_a_file_the_chat_model_read_is_saved_and_no_check_sees_the_key() -> None:
    """The flow #100 found broken: the food is in a file, not in the conversation."""
    plan = "Lunch: salmon bowl, 450 kcal"
    salmon = food_raw(foodName="Salmon bowl", amount=1, unit="bowl", calories=450)
    replies: list[AssistantTurn | Exception] = [
        asked(ToolCall("read", "readFile", '{"path": "meal-plan.md"}')),
        asked(chat(salmon)),
        clear(LIST),
        clear(DATES),
        clear(NUTRITION),
        DONE,
    ]
    _, java, provider = run(replies, Java(files={"meal-plan.md": plan}))

    assert java.saved() == [food_arguments(salmon)]
    # Every check is shown the file, as JSON text: a value can be stated in it too.
    for index in (2, 3, 4):
        assert json.dumps(plan) in provider.prompt(index)
        assert payload()["apiKey"] not in provider.prompt(index)


def test_a_second_call_in_the_same_response_is_shown_what_the_first_saved() -> None:
    """A chat model that spreads a message over two calls must not have a food saved twice,
    so the second call's name and count check sees the first call's result."""
    replies: list[AssistantTurn | Exception] = [
        asked(chat(OATS, call_id="first"), chat(CHICKEN, call_id="second")),
        *(clear(check) for check in (LIST, DATES, NUTRITION) * 2),
        DONE,
    ]
    _, java, provider = run(replies)

    assert java.saved() == [food_arguments(OATS), food_arguments(CHICKEN)]
    first_result = pretty({"recorded": [food_arguments(OATS)["food"]]})
    assert json.dumps(first_result) in provider.prompt(4)
    assert json.dumps(first_result) not in provider.prompt(1)


def test_a_food_the_chat_model_gave_no_date_is_saved_with_one() -> None:
    """In the first eval run of the new checks, `deepseek-flash` left the date out of two
    foods, its date check passed them, and they were saved with no date."""
    replies: list[AssistantTurn | Exception] = [
        asked(chat(food_raw(date=None))),
        clear(LIST),
        clear(DATES),
        answer(CORRECT_FOOD_DATES.name, '{"dates": [{"number": 1, "date": "2026-11-10"}]}'),
        clear(NUTRITION),
        DONE,
    ]
    _, java, provider = run(replies)

    assert java.saved() == [food_arguments(CHICKEN)]
    assert provider.checks() == [LIST, DATES, CORRECT_FOOD_DATES.name, NUTRITION]


def test_with_the_nutrition_check_off_only_the_other_two_checks_run() -> None:
    replies: list[AssistantTurn | Exception] = [asked(chat(SCOOP)), clear(LIST), clear(DATES), DONE]
    _, java, provider = run(replies, settings=RunSettings(check_nutrition=False))

    assert provider.checks() == [LIST, DATES]
    assert java.requests == [("time.current", {}), ("food.insert", food_arguments(SCOOP))]


@pytest.mark.parametrize(
    "before",
    [
        [],
        [clear(LIST)],
        [clear(LIST), clear(DATES)],
        [listed(missing=["1 cup of rice"])],
        [clear(LIST), clear(DATES), clear(NUTRITION)],
    ],
    ids=["names", "dates", "content", "correction", "second-food"],
)
def test_a_provider_failure_between_checks_saves_nothing(before: list[AssistantTurn]) -> None:
    """A message with several foods is saved whole or not at all (#101)."""
    with pytest.raises(AgentFailure) as caught:
        run([asked(chat(CHICKEN, RICE)), *before, FAILURE])
    assert caught.value is FAILURE


def test_a_failed_check_leaves_only_the_clock_request_behind() -> None:
    java = Java()
    with pytest.raises(AgentFailure):
        run([asked(chat(CHICKEN, RICE)), clear(LIST), clear(DATES), FAILURE], java)
    assert java.requests == [("time.current", {})]


def test_a_required_check_that_cannot_be_answered_fails_the_run_and_saves_nothing() -> None:
    java = Java()
    with pytest.raises(AgentFailure) as caught:
        run([asked(chat(CHICKEN)), *[UNUSABLE_TEXT] * REQUIRED_ATTEMPTS], java)
    assert (caught.value.category, caught.value.code) == ("provider", "PROVIDER_FAILURE")
    assert java.saved() == []


class WaitingModel:
    """Ask for recordFood, pass every check before `stage`, then wait inside that check until
    cancelled."""

    def __init__(self, stage: str) -> None:
        self.stage = stage
        self.waiting = asyncio.Event()

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        if [tool.name for tool in tools] == [self.stage]:
            self.waiting.set()
            await asyncio.Event().wait()
        return all_clear(tools) or asked(chat(CHICKEN))

    @asynccontextmanager
    async def open(self, value: Message) -> AsyncIterator[Provider]:
        yield self


@pytest.mark.parametrize("stage", [LIST, DATES, NUTRITION])
def test_cancelling_during_a_check_saves_nothing(stage: str) -> None:
    """Through the production runtime, which runs every check by default."""

    async def scenario() -> None:
        model = WaitingModel(stage)
        sent: asyncio.Queue[Message] = asyncio.Queue()
        runtime = WorkerRuntime(
            lambda kind, request, value: sent.put_nowait({"type": kind, "payload": value}),
            model.open,
        )
        try:
            await runtime.accept({"type": "run", "requestId": "req-1", "payload": payload()})
            clock = await asyncio.wait_for(sent.get(), 3)
            assert clock["payload"]["operation"] == "time.current"
            result = {
                "operationId": clock["payload"]["operationId"],
                "operation": "time.current",
                "outcome": "success",
                "result": {"text": TODAY},
            }
            await runtime.accept(
                {"type": "operation_result", "requestId": "req-1", "payload": result}
            )
            await asyncio.wait_for(model.waiting.wait(), 3)
            await runtime.accept(
                {"type": "cancel", "requestId": "req-1", "payload": {"reason": "user_cancelled"}}
            )
            terminal = await asyncio.wait_for(sent.get(), 3)
            assert terminal["type"] == "failed"
            assert terminal["payload"]["error"]["code"] == "REQUEST_CANCELLED"
            assert terminal["payload"]["error"]["writeOutcome"] == "none"
            assert sent.empty()
        finally:
            await runtime.close()

    asyncio.run(scenario())


# The same checks through each real provider class and SDK, against an in-memory server.


def openai_reply(call: ToolCall) -> Message:
    item = {
        "type": "function_call",
        "call_id": call.call_id,
        "name": call.name,
        "arguments": call.arguments,
    }
    return {"id": "resp_1", "status": "completed", "output": [item]}


def claude_reply(call: ToolCall) -> Message:
    block = {
        "type": "tool_use",
        "id": call.call_id,
        "name": call.name,
        "input": json.loads(call.arguments),
    }
    return {
        "id": "msg_1",
        "type": "message",
        "role": "assistant",
        "model": "configured-model",
        "content": [block],
        "stop_reason": "tool_use",
        "stop_sequence": None,
        "usage": {"input_tokens": 1, "output_tokens": 1},
    }


def deepseek_reply(call: ToolCall) -> Message:
    function = {"name": call.name, "arguments": call.arguments}
    message = {
        "role": "assistant",
        "content": None,
        "tool_calls": [{"id": call.call_id, "type": "function", "function": function}],
    }
    return {
        "id": "chat_1",
        "object": "chat.completion",
        "created": 0,
        "model": "configured-model",
        "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls"}],
    }


def transport(server: Server) -> httpx2.AsyncClient:
    return httpx2.AsyncClient(transport=httpx2.MockTransport(server))


@asynccontextmanager
async def openai(server: Server) -> AsyncIterator[Provider]:
    async with AsyncOpenAI(
        api_key="synthetic", max_retries=0, http_client=transport(server)
    ) as client:
        yield ResponsesProvider(client)


@asynccontextmanager
async def claude(server: Server) -> AsyncIterator[Provider]:
    async with AsyncAnthropic(
        api_key="synthetic", max_retries=0, http_client=transport(server)
    ) as client:
        yield ClaudeProvider(client)


@asynccontextmanager
async def deepseek(server: Server) -> AsyncIterator[Provider]:
    async with AsyncOpenAI(
        api_key="synthetic",
        base_url=DEEPSEEK_BASE_URL,
        max_retries=0,
        http_client=transport(server),
    ) as client:
        yield DeepSeekProvider(client)


def named_tools(body: Message) -> list[str]:
    return [tool["name"] for tool in body["tools"]]


def function_tools(body: Message) -> list[str]:
    return [tool["function"]["name"] for tool in body["tools"]]


type Opener = Callable[[Server], AbstractAsyncContextManager[Provider]]
PROVIDERS: dict[
    str, tuple[Opener, Callable[[ToolCall], Message], Callable[[Message], list[str]]]
] = {
    "openai": (openai, openai_reply, named_tools),
    "anthropic": (claude, claude_reply, named_tools),
    "deepseek": (deepseek, deepseek_reply, function_tools),
}


def record_through(kind: str, server: Server, java: Java, *foods: str) -> str:
    opener = PROVIDERS[kind][0]

    async def scenario() -> str:
        async with opener(server) as provider:
            context = tool_context(payload(), provider, java, DEFAULT_SETTINGS)
            return await ToolAdapters(context).execute("recordFood", foods_raw(*foods))

    return asyncio.run(scenario())


def replies(kind: str, *turns: AssistantTurn) -> Server:
    """A server that answers with these single-call turns, in this provider's wire format."""
    reply = PROVIDERS[kind][1]
    return Server([httpx2.Response(200, json=reply(turn.calls[0])) for turn in turns])


@pytest.mark.parametrize("kind", PROVIDERS)
def test_each_provider_carries_the_three_checks_and_a_passing_food_is_saved(kind: str) -> None:
    server = replies(kind, clear(LIST), clear(DATES), clear(NUTRITION))
    java = Java()
    output = record_through(kind, server, java, SCOOP)
    assert java.saved() == [food_arguments(SCOOP)]
    assert output == pretty({"recorded": [food_arguments(SCOOP)["food"]]})
    tools = PROVIDERS[kind][2]
    assert [tools(server.body(index)) for index in range(3)] == [[LIST], [DATES], [NUTRITION]]


@pytest.mark.parametrize("kind", PROVIDERS)
def test_each_provider_carries_a_removal_an_added_food_and_a_date_correction(kind: str) -> None:
    yesterday = food_raw(
        foodName="Cooked rice", amount=1, unit="cup", calories=205, date="2026-11-09"
    )
    server = replies(
        kind,
        listed(missing=["1 cup of rice"], incorrect=[(1, "The user did not eat it.")]),
        answer(CORRECT_FOOD, RICE),
        answer(DATES, json.dumps({"wrong": flags([(1, "It was yesterday.")])})),
        answer(CORRECT_FOOD_DATES.name, '{"dates": [{"number": 1, "date": "2026-11-09"}]}'),
        clear(NUTRITION),
    )
    java = Java()
    output = record_through(kind, server, java, OATS)
    assert java.saved() == [food_arguments(yesterday)]
    assert json.loads(output)["notRecorded"] == [
        {"foodName": "Oats", "reason": "The user did not eat it."}
    ]
    tools = PROVIDERS[kind][2]
    assert [tools(server.body(index)) for index in range(5)] == [
        [LIST],
        [CORRECT_FOOD],
        [DATES],
        [CORRECT_FOOD_DATES.name],
        [NUTRITION],
    ]


@pytest.mark.parametrize("kind", PROVIDERS)
def test_each_provider_failure_during_a_check_saves_nothing(kind: str) -> None:
    server = Server([httpx2.Response(500, json={"error": {"message": "unavailable"}})])
    java = Java()
    with pytest.raises(AgentFailure) as caught:
        record_through(kind, server, java, CHICKEN)
    assert (caught.value.category, caught.value.code) == ("provider", "PROVIDER_FAILURE")
    assert java.saved() == []
