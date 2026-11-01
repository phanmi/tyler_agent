"""Exercise the pinned SDK through an in-memory HTTP transport, never a live endpoint."""

import asyncio
import json
import logging
import subprocess
import sys

import httpx2
import pytest
from openai import AsyncOpenAI
from support.agent_fakes import payload

from tyler_agent import provider as module
from tyler_agent.failures import AgentFailure
from tyler_agent.provider import (
    AssistantTurn,
    ResponsesProvider,
    ToolDefinition,
    ToolResult,
    UserTurn,
    open_provider,
)

TOOL = ToolDefinition("readFile", "Read a file.", {"type": "object"})
OUTPUT = [
    {"type": "reasoning", "id": "rs_1", "summary": [], "encrypted_content": "opaque"},
    {"type": "message", "content": [{"type": "refusal", "refusal": "no"}]},
    {"type": "function_call", "call_id": "id", "name": "readFile", "arguments": "{}"},
]


@pytest.mark.parametrize(
    "status,category,code",
    [
        (401, "authentication", "PROVIDER_AUTH"),
        (403, "authentication", "PROVIDER_AUTH"),
        (429, "provider", "PROVIDER_FAILURE"),
        (500, "provider", "PROVIDER_FAILURE"),
        (408, "provider", "PROVIDER_FAILURE"),
    ],
)
def test_sdk_failure_classification_cause_and_no_retries(
    status: int, category: str, code: str
) -> None:
    requests: list[httpx2.Request] = []

    def respond(request: httpx2.Request) -> httpx2.Response:
        requests.append(request)
        return httpx2.Response(status, json={"error": {"message": "secret-key prompt file"}})

    async def scenario() -> None:
        async with AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(respond)),
        ) as client:
            with pytest.raises(AgentFailure) as caught:
                await ResponsesProvider(client).respond("model", [], [])
            assert caught.value.category == category and caught.value.code == code
            assert caught.value.__cause__ is not None
            assert "secret" not in str(caught.value.payload("committed"))

    asyncio.run(scenario())
    assert len(requests) == 1


def test_sdk_request_and_response_boundary() -> None:
    requests: list[httpx2.Request] = []

    def respond(request: httpx2.Request) -> httpx2.Response:
        requests.append(request)
        return httpx2.Response(
            200,
            json={"id": "r2", "status": "completed", "output": OUTPUT},
        )

    async def scenario() -> None:
        async with AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(respond)),
        ) as client:
            result = await ResponsesProvider(client).respond(
                "configured", [UserTurn("text"), AssistantTurn((), "earlier")], [TOOL]
            )
            assert result.text == "no" and result.calls[0].call_id == "id"
            assert result.native == tuple(OUTPUT)

    asyncio.run(scenario())
    body = json.loads(requests[0].content)
    assert body == {
        "model": "configured",
        "input": [
            {"role": "user", "content": "text"},
            {"role": "assistant", "content": "earlier"},
        ],
        "tools": [
            {
                "type": "function",
                "name": "readFile",
                "description": "Read a file.",
                "parameters": {"type": "object"},
                "strict": True,
            }
        ],
        "store": False,
        "include": ["reasoning.encrypted_content"],
    }


def test_production_factory_explicit_settings_cleanup_and_sdk_log_redaction(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    created: list[AsyncOpenAI] = []

    def client_factory(**kwargs: object) -> AsyncOpenAI:
        assert kwargs["max_retries"] == 0
        assert kwargs["timeout"] == 60.0
        assert kwargs["base_url"] == "https://api.openai.com/v1"
        assert kwargs["api_key"] == "SYNTHETIC_FIXTURE_KEY"
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
            async with open_provider(payload()) as provider:
                logger.error("secret-key prompt history")
                await provider.respond("model", [], [])

    asyncio.run(scenario())
    assert created[0].is_closed()
    assert "secret" not in caplog.text


def test_transport_failure_no_retry() -> None:
    requests: list[httpx2.Request] = []

    def respond(request: httpx2.Request) -> httpx2.Response:
        requests.append(request)
        raise httpx2.ReadTimeout("private content", request=request)

    async def scenario() -> None:
        async with AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(respond)),
        ) as client:
            with pytest.raises(AgentFailure) as caught:
                await ResponsesProvider(client).respond("model", [], [])
            assert caught.value.code == "PROVIDER_FAILURE" and caught.value.__cause__ is not None

    asyncio.run(scenario())
    assert len(requests) == 1


def test_replays_this_runs_output_and_tool_results_without_server_state() -> None:
    bodies: list[dict[str, object]] = []

    def respond(request: httpx2.Request) -> httpx2.Response:
        bodies.append(json.loads(request.content))
        return httpx2.Response(
            200, json={"id": f"r{len(bodies)}", "status": "completed", "output": OUTPUT}
        )

    async def scenario() -> None:
        async with AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(respond)),
        ) as client:
            provider = ResponsesProvider(client)
            first = await provider.respond("model", [UserTurn("hi")], [TOOL])
            await provider.respond(
                "model", [UserTurn("hi"), first, ToolResult("id", "contents")], [TOOL]
            )

    asyncio.run(scenario())
    assert bodies[1]["input"] == [
        {"role": "user", "content": "hi"},
        *OUTPUT,
        {"type": "function_call_output", "call_id": "id", "output": "contents"},
    ]
    assert all(body["store"] is False and "previous_response_id" not in body for body in bodies)


def test_unset_response_fields_are_not_replayed() -> None:
    def respond(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200,
            json={
                "id": "r",
                "status": "completed",
                "output": [
                    {
                        "type": "message",
                        "id": "msg_1",
                        "role": "assistant",
                        "status": "completed",
                        "content": [{"type": "output_text", "text": "a", "annotations": []}],
                    }
                ],
            },
        )

    async def scenario() -> AssistantTurn:
        async with AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(respond)),
        ) as client:
            return await ResponsesProvider(client).respond("model", [UserTurn("hi")], [])

    turn = asyncio.run(scenario())
    # Exactly what the server sent: no SDK-added nulls such as logprobs.
    assert turn.native == (
        {
            "type": "message",
            "id": "msg_1",
            "role": "assistant",
            "status": "completed",
            "content": [{"type": "output_text", "text": "a", "annotations": []}],
        },
    )
    assert turn.text == "a" and turn.calls == ()


def test_cut_off_response_fails_before_its_tool_call_reaches_the_agent() -> None:
    def respond(request: httpx2.Request) -> httpx2.Response:
        return httpx2.Response(
            200,
            json={
                "id": "r",
                "status": "incomplete",
                "incomplete_details": {"reason": "max_output_tokens"},
                "output": [
                    {"type": "function_call", "call_id": "id", "name": "readFile", "arguments": "{"}
                ],
            },
        )

    async def scenario() -> None:
        async with AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(respond)),
        ) as client:
            with pytest.raises(AgentFailure) as caught:
                await ResponsesProvider(client).respond("model", [UserTurn("hi")], [TOOL])
            assert (caught.value.category, caught.value.code) == ("provider", "PROVIDER_FAILURE")

    asyncio.run(scenario())


# The schema rejects other names first; this guards the dispatch itself.
@pytest.mark.parametrize("name", ["mistral", ""])
def test_unknown_providers_fail_before_any_client(
    name: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    def no_client(**kwargs: object) -> AsyncOpenAI:
        raise AssertionError("No client may be created")

    monkeypatch.setattr(module, "AsyncOpenAI", no_client)

    async def scenario() -> None:
        async with open_provider(payload() | {"provider": name}):
            raise AssertionError("Unreachable")

    with pytest.raises(AgentFailure) as caught:
        asyncio.run(scenario())
    assert (caught.value.category, caught.value.code) == ("provider", "PROVIDER_FAILURE")


def test_neutral_tools_render_as_strict_functions() -> None:
    assert module.responses_tools([TOOL]) == [
        {
            "type": "function",
            "name": "readFile",
            "description": "Read a file.",
            "parameters": {"type": "object"},
            "strict": True,
        }
    ]


def test_worker_startup_does_not_load_the_anthropic_sdk() -> None:
    # A fresh interpreter: importing the provider boundary must leave anthropic unloaded.
    code = "import sys, tyler_agent.provider; print('anthropic' in sys.modules)"
    output = subprocess.run(
        [sys.executable, "-c", code], capture_output=True, text=True, check=True
    ).stdout
    assert output.strip() == "False"
