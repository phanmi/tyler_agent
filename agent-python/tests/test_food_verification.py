"""recordFood verification: the run's model checks each item before Java saves it (#96)."""

import asyncio
import json
import logging
from collections.abc import AsyncIterator, Callable, Sequence
from contextlib import AbstractAsyncContextManager, asynccontextmanager

import httpx2
import pytest
from anthropic import AsyncAnthropic
from openai import AsyncOpenAI
from support.agent_fakes import VERIFICATION_ON, FakeProvider, fake_context, payload
from support.http_server import Server

from tyler_agent.agent import run_agent, tool_context
from tyler_agent.claude_provider import ClaudeProvider
from tyler_agent.deepseek_provider import DEEPSEEK_BASE_URL, DeepSeekProvider
from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import Provider, ResponsesProvider
from tyler_agent.runtime import WorkerRuntime
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.builtin.food_record.verification import (
    MALFORMED_VERDICT,
    NOT_RECORDED,
    VERDICT_TOOL,
    parse_verdict,
)
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

TODAY = "2026-11-10 12:30:00 EST"
CONVERSATION = (
    UserTurn("I had oats for breakfast."),
    AssistantTurn((), "Noted."),
    UserTurn("I ate 200 g of chicken breast for lunch today."),
)


def food_raw(**changes: object) -> str:
    info: dict[str, object] = dict(
        foodName="Chicken breast", amount=200, unit="g", calories=330, date="2026-11-10"
    )
    info.update(changes)
    macros = dict(protein=62, carbs=0, fat=7, fiber=0)
    return json.dumps(dict(genericInfo=info, macroNutrients=macros))


def verdict_call(valid: bool, reason: str) -> ToolCall:
    return ToolCall("verdict", VERDICT_TOOL.name, json.dumps({"valid": valid, "reason": reason}))


def verdict(valid: bool, reason: str = "Matches the conversation.") -> AssistantTurn:
    return AssistantTurn((verdict_call(valid, reason),), "")


def regenerated(**changes: object) -> AssistantTurn:
    return AssistantTurn((ToolCall("fix", "recordFood", food_raw(**changes)),), "")


class Java:
    """Record operation requests; answer the clock and echo saved food like the service does.

    `clocks` gives each clock request its own text, so a test can tell which one a prompt used.
    """

    def __init__(self, clocks: Sequence[str] = ()) -> None:
        self.requests: list[tuple[str, Message]] = []
        self.clocks = list(clocks)

    async def __call__(self, name: str, value: Message) -> Message:
        self.requests.append((name, value))
        if name == "time.current":
            return {"text": self.clocks.pop(0) if self.clocks else TODAY}
        assert name == "food.insert"
        return {"food": value["food"]}

    def saved(self) -> list[Message]:
        return [value for name, value in self.requests if name == "food.insert"]


class Model:
    """Answer verification requests from a script, recording each prompt, tool list, and how
    many items Java had saved when the request was made."""

    def __init__(self, java: Java, replies: list[AssistantTurn | Exception]) -> None:
        self.java = java
        self.replies = replies
        self.prompts: list[str] = []
        self.tools: list[list[str]] = []
        self.saved_at_request: list[int] = []

    async def __call__(
        self, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        (turn,) = transcript
        assert isinstance(turn, UserTurn)
        self.prompts.append(turn.text)
        self.tools.append([tool.name for tool in tools])
        self.saved_at_request.append(len(self.java.saved()))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply


def record(replies: list[AssistantTurn | Exception], raw: str = "") -> tuple[str, Java, Model]:
    java = Java()
    model = Model(java, replies)
    tools = ToolAdapters(fake_context(java, model, CONVERSATION, VERIFICATION_ON))
    output = asyncio.run(tools.execute("recordFood", raw or food_raw()))
    return output, java, model


def saved_output(raw: str) -> str:
    return pretty(food_arguments(raw)["food"])


def test_an_accepted_item_is_saved_unchanged_after_one_model_request() -> None:
    output, java, model = record([verdict(True)])
    assert java.requests == [("time.current", {}), ("food.insert", food_arguments(food_raw()))]
    assert output == saved_output(food_raw())
    assert model.tools == [[VERDICT_TOOL.name]]
    assert model.saved_at_request == [0]


def test_the_verifier_sees_the_conversation_date_and_record() -> None:
    _, _, model = record([verdict(True)])
    (prompt,) = model.prompts
    assert TODAY in prompt and food_raw() in prompt
    for turn in CONVERSATION:
        assert json.dumps(turn.text) in prompt


def test_a_rejected_item_is_regenerated_with_the_reason_and_saved_when_accepted() -> None:
    output, java, model = record(
        [verdict(False, "The user said 250 g."), regenerated(amount=250), verdict(True)]
    )
    assert java.saved() == [food_arguments(food_raw(amount=250))]
    assert output == saved_output(food_raw(amount=250))
    assert model.tools == [[VERDICT_TOOL.name], ["recordFood"], [VERDICT_TOOL.name]]
    assert "The user said 250 g." in model.prompts[1] and food_raw() in model.prompts[1]
    assert TODAY in model.prompts[1]
    assert food_raw(amount=250) in model.prompts[2]
    # Only the check of a correction also sees the record it replaces.
    assert "replaces the rejected record" in model.prompts[2] and food_raw() in model.prompts[2]
    assert "replaces the rejected record" not in model.prompts[0]
    assert model.saved_at_request == [0, 0, 0]


def test_an_item_rejected_twice_is_not_saved() -> None:
    output, java, model = record(
        [verdict(False, "Wrong amount."), regenerated(amount=250), verdict(False, "It was 300 g.")]
    )
    assert java.requests == [("time.current", {})]
    assert json.loads(output) == {"error": NOT_RECORDED, "reason": "It was 300 g."}
    assert len(model.prompts) == 3


def verdict_arguments(raw: str) -> AssistantTurn:
    return AssistantTurn((ToolCall("verdict", VERDICT_TOOL.name, raw),), "")


@pytest.mark.parametrize(
    "turn",
    [
        AssistantTurn((), "The record is valid."),
        AssistantTurn((ToolCall("verdict", "recordFood", food_raw()),), ""),
        AssistantTurn((verdict_call(True, "ok"), verdict_call(True, "ok")), ""),
        verdict_arguments('{"valid": "true", "reason": "ok"}'),
        verdict_arguments('{"valid": 1, "reason": "ok"}'),
        verdict_arguments('{"valid": true}'),
        verdict_arguments('{"valid": true, "reason": null}'),
        verdict_arguments('{"valid": true, "reason": "ok", "extra": 1}'),
        verdict_arguments('{"valid": true, "valid": true, "reason": "ok"}'),
        verdict_arguments('{"valid": true, "reason": "ok"} trailing'),
        verdict_arguments('{"valid": true, "reason": "\\ud800"}'),
        verdict_arguments('{"valid": false, "reason": " "}'),
        verdict_arguments("[true]"),
    ],
)
def test_anything_but_one_well_formed_verdict_is_a_rejection(turn: AssistantTurn) -> None:
    assert parse_verdict(turn) == MALFORMED_VERDICT


def test_unusable_verdicts_never_lead_to_a_save() -> None:
    unusable = AssistantTurn((), "valid")
    output, java, model = record([unusable, unusable])
    assert java.saved() == []
    assert json.loads(output) == {"error": NOT_RECORDED, "reason": MALFORMED_VERDICT.reason}
    assert model.tools == [[VERDICT_TOOL.name], [VERDICT_TOOL.name]]


def test_after_an_unusable_verdict_the_same_item_is_checked_again_not_regenerated() -> None:
    output, java, model = record([AssistantTurn((), "It looks right."), verdict(True)])
    assert java.saved() == [food_arguments(food_raw())]
    assert output == saved_output(food_raw())
    assert model.tools == [[VERDICT_TOOL.name], [VERDICT_TOOL.name]]
    assert all(food_raw() in prompt for prompt in model.prompts)


def test_a_rejection_after_an_unusable_verdict_refuses_with_the_real_reason() -> None:
    output, java, _ = record([AssistantTurn((), "Hmm."), verdict(False, "It was 300 g.")])
    assert java.saved() == []
    assert json.loads(output)["reason"] == "It was 300 g."


@pytest.mark.parametrize(
    "correction",
    [
        AssistantTurn((), "Here is the corrected record."),
        verdict(True),
        AssistantTurn((ToolCall("fix", "recordFood", '{"genericInfo": {"foodName": 5}}'),), ""),
        AssistantTurn((ToolCall("a", "recordFood", food_raw()),) * 2, ""),
        # Matches the schema but exceeds the protocol's two-billion bound.
        regenerated(calories=3000000000),
    ],
)
def test_a_malformed_regeneration_counts_as_a_rejection(correction: AssistantTurn) -> None:
    output, java, model = record([verdict(False, "Wrong amount."), correction])
    assert java.saved() == []
    assert json.loads(output) == {"error": NOT_RECORDED, "reason": "Wrong amount."}
    assert len(model.prompts) == 2


@pytest.mark.parametrize(
    "replies",
    [
        [AgentFailure("provider", "PROVIDER_FAILURE")],
        [verdict(False, "Wrong amount."), AgentFailure("provider", "PROVIDER_FAILURE")],
        [verdict(False, "Wrong."), regenerated(), AgentFailure("provider", "PROVIDER_FAILURE")],
    ],
)
def test_a_provider_failure_fails_the_run_and_saves_nothing(
    replies: list[AssistantTurn | Exception],
) -> None:
    java = Java()
    tools = ToolAdapters(fake_context(java, Model(java, replies), CONVERSATION, VERIFICATION_ON))
    with pytest.raises(AgentFailure) as caught:
        asyncio.run(tools.execute("recordFood", food_raw()))
    assert (caught.value.category, caught.value.code) == ("provider", "PROVIDER_FAILURE")
    assert java.saved() == []


def test_with_verification_off_the_item_is_saved_without_model_requests() -> None:
    java = Java()
    # fake_context's model fails the test if a tool makes any model request.
    output = asyncio.run(ToolAdapters(fake_context(java)).execute("recordFood", food_raw()))
    assert java.requests == [("food.insert", food_arguments(food_raw()))]
    assert output == saved_output(food_raw())


def test_logs_record_the_outcome_but_no_food_reason_or_conversation(
    caplog: pytest.LogCaptureFixture,
) -> None:
    caplog.set_level(logging.INFO, logger="tyler_agent")
    record([verdict(False, "PRIVATE-REASON"), regenerated(foodName="PRIVATE-FOOD"), verdict(True)])
    record([verdict(False, "PRIVATE-REASON"), regenerated(), verdict(False, "PRIVATE-REASON")])
    messages = [entry.getMessage() for entry in caplog.records]
    assert "Food record verified: rejections=1" in messages
    assert "Food record refused by verification: rejections=2" in messages
    text = "\n".join(messages)
    for private in ("PRIVATE-REASON", "PRIVATE-FOOD", "Chicken", "chicken", TODAY):
        assert private not in text


def test_the_verifier_never_sees_tool_results_or_the_key() -> None:
    """Through the real agent loop: an earlier tool result must not reach the verifier."""
    data = payload()
    data["history"] = [{"role": "user", "content": "Hello"}, {"role": "assistant", "content": "Hi"}]
    provider = FakeProvider(
        [
            AssistantTurn((ToolCall("clock", "getCurrentTime", "{}"),), ""),
            AssistantTurn((ToolCall("food", "recordFood", food_raw()),), ""),
            verdict(True),
            AssistantTurn((), "Recorded."),
        ]
    )
    # The chat's getCurrentTime result and the verifier's own date request get different text.
    java = Java(["TOOL-RESULT-CLOCK", TODAY])

    async def scenario() -> str:
        context = tool_context(data, provider, java, VERIFICATION_ON)
        return await run_agent(data, provider, ToolAdapters(context))

    assert asyncio.run(scenario()) == "Recorded."
    check = provider.requests[2]
    assert [tool.name for tool in check["tools"]] == [VERDICT_TOOL.name]
    (turn,) = check["transcript"]
    assert isinstance(turn, UserTurn)
    assert json.dumps("Hello") in turn.text and json.dumps("current") in turn.text
    assert TODAY in turn.text
    assert "TOOL-RESULT-CLOCK" not in turn.text and data["apiKey"] not in turn.text
    assert not any(isinstance(entry, ToolResult) for entry in check["transcript"])
    assert java.saved() == [food_arguments(food_raw())]


class WaitingModel:
    """Ask for recordFood, then wait inside verification at the chosen stage until cancelled."""

    def __init__(self, stage: str) -> None:
        self.stage = stage
        self.waiting = asyncio.Event()

    async def respond(
        self, model: str, transcript: Sequence[TranscriptEntry], tools: Sequence[ToolDefinition]
    ) -> AssistantTurn:
        names = [tool.name for tool in tools]
        if names == [VERDICT_TOOL.name] and self.stage == "regenerate":
            return verdict(False, "Wrong amount.")
        if names in ([VERDICT_TOOL.name], ["recordFood"]):
            self.waiting.set()
            await asyncio.Event().wait()
        return AssistantTurn((ToolCall("food", "recordFood", food_raw()),), "")

    @asynccontextmanager
    async def open(self, value: Message) -> AsyncIterator[Provider]:
        yield self


@pytest.mark.parametrize("stage", ["verify", "regenerate"])
def test_cancelling_during_verification_saves_nothing(stage: str) -> None:
    """The production runtime verifies by default; cancelling mid-check sends no write."""

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


# The same decisions through each real provider class and SDK, against an in-memory server.


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


def openai_tools(body: Message) -> list[str]:
    return [tool["name"] for tool in body["tools"]]


def claude_tools(body: Message) -> list[str]:
    return [tool["name"] for tool in body["tools"]]


def deepseek_tools(body: Message) -> list[str]:
    return [tool["function"]["name"] for tool in body["tools"]]


type Opener = Callable[[Server], AbstractAsyncContextManager[Provider]]
PROVIDERS: dict[
    str, tuple[Opener, Callable[[ToolCall], Message], Callable[[Message], list[str]]]
] = {
    "openai": (openai, openai_reply, openai_tools),
    "anthropic": (claude, claude_reply, claude_tools),
    "deepseek": (deepseek, deepseek_reply, deepseek_tools),
}


def record_through(kind: str, server: Server, java: Java) -> str:
    opener = PROVIDERS[kind][0]

    async def scenario() -> str:
        async with opener(server) as provider:
            context = tool_context(payload(), provider, java, VERIFICATION_ON)
            return await ToolAdapters(context).execute("recordFood", food_raw())

    return asyncio.run(scenario())


@pytest.mark.parametrize("kind", PROVIDERS)
def test_each_provider_carries_the_verdict_and_an_accepted_item_is_saved(kind: str) -> None:
    _, reply, tools = PROVIDERS[kind]
    server = Server([httpx2.Response(200, json=reply(verdict_call(True, "Matches.")))])
    java = Java()
    output = record_through(kind, server, java)
    assert java.saved() == [food_arguments(food_raw())]
    assert output == saved_output(food_raw())
    assert tools(server.body(0)) == [VERDICT_TOOL.name]


@pytest.mark.parametrize("kind", PROVIDERS)
def test_each_provider_regenerates_and_refuses_an_item_rejected_twice(kind: str) -> None:
    _, reply, tools = PROVIDERS[kind]
    fix = ToolCall("fix", "recordFood", food_raw(amount=250))
    server = Server(
        [
            httpx2.Response(200, json=reply(verdict_call(False, "Wrong amount."))),
            httpx2.Response(200, json=reply(fix)),
            httpx2.Response(200, json=reply(verdict_call(False, "It was 300 g."))),
        ]
    )
    java = Java()
    output = record_through(kind, server, java)
    assert java.saved() == []
    assert json.loads(output) == {"error": NOT_RECORDED, "reason": "It was 300 g."}
    assert [tools(server.body(index)) for index in range(3)] == [
        [VERDICT_TOOL.name],
        ["recordFood"],
        [VERDICT_TOOL.name],
    ]


@pytest.mark.parametrize("kind", PROVIDERS)
def test_each_provider_failure_during_verification_saves_nothing(kind: str) -> None:
    server = Server([httpx2.Response(500, json={"error": {"message": "unavailable"}})])
    java = Java()
    with pytest.raises(AgentFailure) as caught:
        record_through(kind, server, java)
    assert (caught.value.category, caught.value.code) == ("provider", "PROVIDER_FAILURE")
    assert java.saved() == []
