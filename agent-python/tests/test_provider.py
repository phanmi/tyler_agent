"""Exercise the pinned SDK through an in-memory HTTP transport, never a live endpoint."""

import asyncio
import json
import logging

import httpx2
import pytest
from openai import AsyncOpenAI
from support.agent_fakes import payload

from tyler_agent import provider as module
from tyler_agent.failures import AgentFailure
from tyler_agent.provider import ResponsesProvider, open_provider


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
                await ResponsesProvider(client).respond("model", [], [], None)
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
            json={
                "id": "r2",
                "output": [
                    {"type": "message", "content": [{"type": "refusal", "refusal": "no"}]},
                    {
                        "type": "function_call",
                        "call_id": "id",
                        "name": "readFile",
                        "arguments": "{}",
                    },
                ],
            },
        )

    async def scenario() -> None:
        async with AsyncOpenAI(
            api_key="synthetic",
            max_retries=0,
            http_client=httpx2.AsyncClient(transport=httpx2.MockTransport(respond)),
        ) as client:
            result = await ResponsesProvider(client).respond(
                "configured", [{"role": "user", "content": "text"}], [], "r1"
            )
            assert result.text == "no" and result.calls[0].call_id == "id"

    asyncio.run(scenario())
    body = json.loads(requests[0].content)
    assert body == {
        "model": "configured",
        "input": [{"role": "user", "content": "text"}],
        "tools": [],
        "previous_response_id": "r1",
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
                await provider.respond("model", [], [], None)

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
                await ResponsesProvider(client).respond("model", [], [], None)
            assert caught.value.code == "PROVIDER_FAILURE" and caught.value.__cause__ is not None

    asyncio.run(scenario())
    assert len(requests) == 1
