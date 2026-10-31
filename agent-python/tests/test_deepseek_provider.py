"""Exercise DeepSeek through the pinned openai SDK and in-memory transport, never live."""

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

import httpx2
import pytest
from openai import AsyncOpenAI
from support.agent_fakes import payload

from tyler_agent import deepseek_provider as module
from tyler_agent.agent import run_agent
from tyler_agent.deepseek_provider import DeepSeekProvider, chat_messages, parse_completion
from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import open_provider
from tyler_agent.tools import ToolAdapters
from tyler_agent.transcript import (
    AssistantTurn,
    ToolDefinition,
    ToolResult,
    TranscriptEntry,
    UserTurn,
)

TOOL = ToolDefinition("readFile", "Read a file.", {"type": "object"})


def call(call_id: str, name: str = "readFile", arguments: str = "{}") -> Message:
    return {"id": call_id, "type": "function", "function": {"name": name, "arguments": arguments}}


def completion(
    content: str | None, calls: list[Message] | None = None, finish: str | None = "stop"
) -> Message:
    message: Message = {"role": "assistant", "content": content}
    if calls is not None:
        message["tool_calls"] = calls
    return {
        "id": "chat_1",
        "object": "chat.completion",
        "created": 0,
        "model": "deepseek-flash",
        "choices": [{"index": 0, "message": message, "finish_reason": finish}],
    }


class Server:
    """Record request bodies and answer from a script, one response per request."""

    def __init__(self, responses: list[httpx2.Response]) -> None:
        self.responses = responses
        self.requests: list[httpx2.Request] = []

    def __call__(self, request: httpx2.Request) -> httpx2.Response:
        self.requests.append(request)
        return self.responses.pop(0)

    def body(self, index: int) -> Any:
        return json.loads(self.requests[index].content)


def run[T](
    server: Callable[[httpx2.Request], httpx2.Response],
    scenario: Callable[[DeepSeekProvider], Awaitable[T]],
) -> T:
    async def wrapped() -> T:
        async with AsyncOpenAI(
            api_key="synthetic",
            base_url=module.DEEPSEEK_BASE_URL,
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(server)),
        ) as client:
            return await scenario(DeepSeekProvider(client))

    return asyncio.run(wrapped())


def test_text_reply_request_shape() -> None:
    server = Server([httpx2.Response(200, json=completion("Hi there"))])
    transcript: list[TranscriptEntry] = [
        UserTurn("old"),
        AssistantTurn((), "earlier"),
        UserTurn("current"),
    ]

    turn = run(server, lambda provider: provider.respond("deepseek-flash", transcript, [TOOL]))

    assert turn.text == "Hi there" and turn.calls == ()
    assert server.requests[0].url == "https://api.deepseek.com/chat/completions"
    assert server.body(0) == {
        "model": "deepseek-flash",
        "messages": [
            {"role": "user", "content": "old"},
            {"role": "assistant", "content": "earlier"},
            {"role": "user", "content": "current"},
        ],
        "tools": [
            {
                "type": "function",
                "function": {
                    "name": "readFile",
                    "description": "Read a file.",
                    "parameters": {"type": "object"},
                },
            }
        ],
        "thinking": {"type": "disabled"},
    }


def test_no_tools_omits_the_tools_field() -> None:
    server = Server([httpx2.Response(200, json=completion("ok"))])
    run(server, lambda provider: provider.respond("model", [UserTurn("hi")], []))
    assert "tools" not in server.body(0)


def test_multi_round_tool_use_replays_calls_then_tool_messages() -> None:
    first = completion(
        "Checking.", [call("a", arguments='{"path": "x"}'), call("b")], finish="tool_calls"
    )
    first["choices"][0]["message"]["reasoning_content"] = "never replayed"
    server = Server(
        [httpx2.Response(200, json=first), httpx2.Response(200, json=completion("Done"))]
    )

    async def scenario(provider: DeepSeekProvider) -> AssistantTurn:
        turn = await provider.respond("model", [UserTurn("go")], [TOOL])
        assert [(c.call_id, c.name, c.arguments) for c in turn.calls] == [
            ("a", "readFile", '{"path": "x"}'),
            ("b", "readFile", "{}"),
        ]
        transcript: list[TranscriptEntry] = [
            UserTurn("go"),
            turn,
            ToolResult("a", "one"),
            ToolResult("b", "two"),
        ]
        return await provider.respond("model", transcript, [TOOL])

    assert run(server, scenario).text == "Done"
    assert server.body(1)["messages"] == [
        {"role": "user", "content": "go"},
        {
            "role": "assistant",
            "content": "Checking.",
            "tool_calls": [call("a", arguments='{"path": "x"}'), call("b")],
        },
        {"role": "tool", "tool_call_id": "a", "content": "one"},
        {"role": "tool", "tool_call_id": "b", "content": "two"},
    ]


def test_agent_runs_deepseek_tool_rounds_end_to_end() -> None:
    server = Server(
        [
            httpx2.Response(
                200, json=completion(None, [call("t1", "getCurrentTime")], finish="tool_calls")
            ),
            httpx2.Response(200, json=completion("It is clock.")),
        ]
    )

    async def clock(name: str, value: Message) -> Message:
        assert name == "time.current"
        return {"text": "clock"}

    async def scenario(provider: DeepSeekProvider) -> str:
        return await run_agent(payload(), provider, ToolAdapters(clock))

    assert run(server, scenario) == "It is clock."
    messages = server.body(1)["messages"]
    assert messages[-2]["role"] == "assistant" and messages[-2]["content"] is None
    assert messages[-1] == {
        "role": "tool",
        "tool_call_id": "t1",
        "content": messages[-1]["content"],
    }


def test_saved_empty_reply_and_null_content_render_as_chat_messages() -> None:
    assert chat_messages([UserTurn("a"), AssistantTurn((), ""), UserTurn("b")]) == [
        {"role": "user", "content": "a"},
        {"role": "assistant", "content": ""},
        {"role": "user", "content": "b"},
    ]
    assert parse_completion(completion(None)) == AssistantTurn(
        (), "", ({"role": "assistant", "content": None},)
    )


@pytest.mark.parametrize(
    "finish", ["length", "content_filter", "insufficient_system_resource", "aborted", None]
)
def test_incomplete_finishes_fail_before_content_is_read(finish: str | None) -> None:
    with pytest.raises(AgentFailure) as caught:
        parse_completion(completion(None, [call("cut-short")], finish=finish))
    assert caught.value.code == "PROVIDER_FAILURE"


def _with_message(message: Any) -> Message:
    value = completion("x")
    value["choices"][0]["message"] = message
    return value


@pytest.mark.parametrize(
    "value",
    [
        {"choices": None},
        {"choices": []},
        {"choices": [None]},
        _with_message(None),
        _with_message({"role": "assistant", "content": 3}),
        _with_message({"role": "assistant", "content": "\ud800"}),
        _with_message({"role": "assistant", "content": None, "tool_calls": "x"}),
        _with_message({"role": "assistant", "content": None, "tool_calls": [None]}),
        _with_message({"role": "assistant", "content": None, "tool_calls": [call("")]}),
        _with_message(
            {"role": "assistant", "content": None, "tool_calls": [call("a", arguments={})]}  # type: ignore[arg-type]
        ),
        _with_message(
            {"role": "assistant", "content": None, "tool_calls": [{"id": "a", "type": "custom"}]}
        ),
        _with_message(
            {"role": "assistant", "content": None, "tool_calls": [call(str(i)) for i in range(101)]}
        ),
    ],
)
def test_malformed_completion_is_a_safe_failure(value: Message) -> None:
    value.setdefault("choices", [])
    if value["choices"] and isinstance(value["choices"][0], dict):
        value["choices"][0]["finish_reason"] = "tool_calls"
    with pytest.raises(AgentFailure) as caught:
        parse_completion(value)
    assert caught.value.code == "PROVIDER_FAILURE"


@pytest.mark.parametrize(
    "status,category,code",
    [
        (401, "authentication", "PROVIDER_AUTH"),
        (403, "authentication", "PROVIDER_AUTH"),
        (400, "provider", "PROVIDER_FAILURE"),
        (402, "provider", "PROVIDER_FAILURE"),
        (422, "provider", "PROVIDER_FAILURE"),
        (429, "provider", "PROVIDER_FAILURE"),
        (500, "provider", "PROVIDER_FAILURE"),
        (503, "provider", "PROVIDER_FAILURE"),
    ],
)
def test_sdk_failure_classification_cause_and_no_retries(
    status: int, category: str, code: str
) -> None:
    server = Server([httpx2.Response(status, json={"error": {"message": "secret-key prompt"}})])

    async def scenario(provider: DeepSeekProvider) -> None:
        with pytest.raises(AgentFailure) as caught:
            await provider.respond("model", [UserTurn("hi")], [])
        assert caught.value.category == category and caught.value.code == code
        assert caught.value.__cause__ is not None
        assert "secret" not in str(caught.value.payload("committed"))

    run(server, scenario)
    assert len(server.requests) == 1


def test_transport_failure_no_retry() -> None:
    requests: list[httpx2.Request] = []

    def server(request: httpx2.Request) -> httpx2.Response:
        requests.append(request)
        raise httpx2.ReadTimeout("private content", request=request)

    async def scenario(provider: DeepSeekProvider) -> None:
        with pytest.raises(AgentFailure) as caught:
            await provider.respond("model", [UserTurn("hi")], [])
        assert caught.value.code == "PROVIDER_FAILURE" and caught.value.__cause__ is not None

    run(server, scenario)
    assert len(requests) == 1


def test_production_factory_explicit_settings_cleanup_and_sdk_log_redaction(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    created: list[AsyncOpenAI] = []

    def client_factory(**kwargs: object) -> AsyncOpenAI:
        assert kwargs == {
            "api_key": "SYNTHETIC_FIXTURE_KEY",
            "base_url": "https://api.deepseek.com",
            "max_retries": 0,
            "timeout": 60.0,
        }
        client = AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(
                transport=httpx2.MockTransport(
                    lambda request: httpx2.Response(500, json={"error": {"message": "secret"}})
                )
            ),
        )
        created.append(client)
        return client

    monkeypatch.setattr(module, "AsyncOpenAI", client_factory)
    logger = logging.getLogger("openai._base_client")
    logger.setLevel(logging.DEBUG)
    caplog.set_level(logging.DEBUG)

    async def scenario() -> None:
        with pytest.raises(AgentFailure):
            async with open_provider(payload() | {"provider": "deepseek"}) as provider:
                logger.error("secret-key prompt history")
                await provider.respond("model", [UserTurn("hi")], [])

    asyncio.run(scenario())
    assert created[0].is_closed()
    assert "secret" not in caplog.text
