"""Exercise the pinned Anthropic SDK through an in-memory HTTP transport, never a live endpoint."""

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable
from typing import Any

import httpx2
import pytest
from anthropic import AsyncAnthropic
from support.agent_fakes import payload

from tyler_agent import claude_provider as module
from tyler_agent.agent import run_agent
from tyler_agent.claude_provider import (
    REFUSAL_REPLY,
    ClaudeProvider,
    claude_messages,
    parse_message,
)
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

TOOL = ToolDefinition(
    "readFile",
    "Read a file.",
    {"type": "object", "properties": {}, "additionalProperties": False, "required": []},
)
THINKING = {"type": "thinking", "thinking": "", "signature": "opaque-signature"}


def message(content: list[Message], stop: str | None = "end_turn") -> Message:
    return {
        "id": "msg_1",
        "type": "message",
        "role": "assistant",
        "model": "claude-sonnet-5-5",
        "content": content,
        "stop_reason": stop,
        "stop_sequence": None,
        "usage": {"input_tokens": 1, "output_tokens": 1},
    }


def tool_use(call_id: str, name: str = "readFile", value: Message | None = None) -> Message:
    return {"type": "tool_use", "id": call_id, "name": name, "input": value or {}}


def text(value: str) -> Message:
    return {"type": "text", "text": value}


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
    scenario: Callable[[ClaudeProvider], Awaitable[T]],
) -> T:
    async def wrapped() -> T:
        async with AsyncAnthropic(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(server)),
        ) as client:
            return await scenario(ClaudeProvider(client))

    return asyncio.run(wrapped())


def test_text_reply_request_shape() -> None:
    server = Server([httpx2.Response(200, json=message([THINKING, text("Hi"), text(" there")]))])
    transcript: list[TranscriptEntry] = [
        UserTurn("old"),
        AssistantTurn((), "earlier"),
        UserTurn("current"),
    ]

    turn = run(server, lambda provider: provider.respond("claude-sonnet-5-5", transcript, [TOOL]))

    assert turn.text == "Hi there" and turn.calls == ()
    assert server.requests[0].headers["anthropic-beta"] == "server-side-fallback-2026-07-01"
    assert server.body(0) == {
        "model": "claude-sonnet-5-5",
        "max_tokens": 16000,
        "messages": [
            {"role": "user", "content": "old"},
            {"role": "assistant", "content": "earlier"},
            {"role": "user", "content": "current"},
        ],
        "tools": [
            {
                "name": "readFile",
                "description": "Read a file.",
                "input_schema": TOOL.parameters,
                "strict": True,
            }
        ],
        "thinking": {"type": "adaptive"},
        "fallbacks": "default",
    }


def test_multi_round_tool_use_replays_content_and_groups_results() -> None:
    first = message(
        [THINKING, text("Checking."), tool_use("a", value={"path": "x"}), tool_use("b")],
        stop="tool_use",
    )
    server = Server(
        [httpx2.Response(200, json=first), httpx2.Response(200, json=message([text("Done")]))]
    )

    async def scenario(provider: ClaudeProvider) -> AssistantTurn:
        turn = await provider.respond("model", [UserTurn("go")], [TOOL])
        assert [(call.call_id, call.arguments) for call in turn.calls] == [
            ("a", '{"path": "x"}'),
            ("b", "{}"),
        ]
        transcript: list[TranscriptEntry] = [
            UserTurn("go"),
            turn,
            ToolResult("a", "one"),
            ToolResult("b", "two"),
        ]
        return await provider.respond("model", transcript, [TOOL])

    assert run(server, scenario) == AssistantTurn((), "Done", (text("Done"),))
    assert server.body(1)["messages"] == [
        {"role": "user", "content": "go"},
        # The thinking block, signature included, returns exactly as Claude sent it.
        {"role": "assistant", "content": first["content"]},
        {
            "role": "user",
            "content": [
                {"type": "tool_result", "tool_use_id": "a", "content": "one"},
                {"type": "tool_result", "tool_use_id": "b", "content": "two"},
            ],
        },
    ]


def test_agent_runs_claude_tool_rounds_end_to_end() -> None:
    server = Server(
        [
            httpx2.Response(200, json=message([tool_use("t1", "getCurrentTime")], stop="tool_use")),
            httpx2.Response(200, json=message([text("It is clock.")])),
        ]
    )

    async def clock(name: str, value: Message) -> Message:
        assert name == "time.current"
        return {"text": "clock"}

    async def scenario(provider: ClaudeProvider) -> str:
        return await run_agent(payload(), provider, ToolAdapters(clock))

    assert run(server, scenario) == "It is clock."
    results = server.body(1)["messages"][-1]
    assert results["role"] == "user"
    assert [block["tool_use_id"] for block in results["content"]] == ["t1"]


def test_refusal_returns_fixed_reply_and_runs_no_tool() -> None:
    refused = message([tool_use("unsafe", "writeFile")], stop="refusal")
    turn = parse_message(refused)
    assert turn == AssistantTurn((), REFUSAL_REPLY)


@pytest.mark.parametrize(
    "stop", ["max_tokens", "model_context_window_exceeded", "pause_turn", "compaction", None]
)
def test_incomplete_stops_fail_before_content_is_read(stop: str | None) -> None:
    with pytest.raises(AgentFailure) as caught:
        parse_message(message([tool_use("cut-short")], stop=stop))
    assert caught.value.code == "PROVIDER_FAILURE"


def test_declined_blocks_before_a_fallback_are_not_replayed_or_run() -> None:
    fallback = {
        "type": "fallback",
        "from": {"model": "claude-sonnet-5-5"},
        "to": {"model": "claude-sonnet-5"},
        "trigger": {"type": "refusal"},
    }
    content = [THINKING, tool_use("declined"), text("Partial."), fallback, text("Answer.")]
    turn = parse_message(message(content))
    assert turn.calls == ()
    assert turn.text == "Partial.Answer."
    assert turn.native == (text("Partial."), text("Answer."))


@pytest.mark.parametrize(
    "content",
    [
        None,
        [None],
        [{"type": "text", "text": 3}],
        [{"type": "tool_use", "id": "a", "name": "readFile", "input": "{}"}],
        [{"type": "tool_use", "id": "", "name": "readFile", "input": {}}],
        [{"type": "text", "text": "\ud800"}],
        [text("x")] * 1001,
        [tool_use(str(index)) for index in range(101)],
    ],
)
def test_malformed_content_is_a_safe_failure(content: Any) -> None:
    with pytest.raises(AgentFailure) as caught:
        parse_message(message(content, stop="tool_use"))
    assert caught.value.code == "PROVIDER_FAILURE"


def test_empty_saved_turns_are_skipped_and_results_follow_their_turn() -> None:
    call_turn = AssistantTurn((), "", (tool_use("a"),))
    transcript: list[TranscriptEntry] = [
        UserTurn("first"),
        AssistantTurn((), ""),
        UserTurn("second"),
        call_turn,
        ToolResult("a", "out"),
    ]
    assert claude_messages(transcript) == [
        {"role": "user", "content": "first"},
        {"role": "user", "content": "second"},
        {"role": "assistant", "content": [tool_use("a")]},
        {
            "role": "user",
            "content": [{"type": "tool_result", "tool_use_id": "a", "content": "out"}],
        },
    ]


@pytest.mark.parametrize(
    "status,category,code",
    [
        (401, "authentication", "PROVIDER_AUTH"),
        (403, "authentication", "PROVIDER_AUTH"),
        (400, "provider", "PROVIDER_FAILURE"),
        (429, "provider", "PROVIDER_FAILURE"),
        (500, "provider", "PROVIDER_FAILURE"),
        (529, "provider", "PROVIDER_FAILURE"),
    ],
)
def test_sdk_failure_classification_cause_and_no_retries(
    status: int, category: str, code: str
) -> None:
    error = {"type": "error", "error": {"type": "api_error", "message": "secret-key prompt"}}
    server = Server([httpx2.Response(status, json=error)])

    async def scenario(provider: ClaudeProvider) -> None:
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

    async def scenario(provider: ClaudeProvider) -> None:
        with pytest.raises(AgentFailure) as caught:
            await provider.respond("model", [UserTurn("hi")], [])
        assert caught.value.code == "PROVIDER_FAILURE" and caught.value.__cause__ is not None

    run(server, scenario)
    assert len(requests) == 1


def test_production_factory_explicit_settings_cleanup_and_sdk_log_redaction(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    created: list[AsyncAnthropic] = []

    def client_factory(**kwargs: object) -> AsyncAnthropic:
        assert kwargs == {
            "api_key": "SYNTHETIC_FIXTURE_KEY",
            "base_url": "https://api.anthropic.com",
            "max_retries": 0,
            "timeout": 60.0,
            "open_telemetry": {"traces": False, "propagation": False},
        }
        client = AsyncAnthropic(
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

    monkeypatch.setattr(module, "AsyncAnthropic", client_factory)
    logger = logging.getLogger("anthropic._base_client")
    logger.setLevel(logging.DEBUG)
    caplog.set_level(logging.DEBUG)

    async def scenario() -> None:
        with pytest.raises(AgentFailure):
            async with open_provider(payload() | {"provider": "anthropic"}) as provider:
                logger.error("secret-key prompt history")
                await provider.respond("model", [UserTurn("hi")], [])

    asyncio.run(scenario())
    assert created[0].is_closed()
    assert "secret" not in caplog.text
