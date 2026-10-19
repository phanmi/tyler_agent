"""Step 2 behavior parity without a live model or application data."""

import asyncio
from copy import deepcopy

import pytest
from support.agent_fakes import FakeProvider, call, payload

from tyler_agent.agent import run_agent
from tyler_agent.failures import AgentFailure, OperationFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.provider import ModelResponse, parse_response
from tyler_agent.tools import ToolAdapters


async def clock_operation(name: str, value: Message) -> Message:
    assert name == "time.current" and value == {}
    return {"text": "clock"}


def test_initial_context_model_and_fresh_tool_definitions() -> None:
    data = payload()
    data["history"] = [
        {"role": "user", "content": "old"},
        {"role": "assistant", "content": "reply"},
    ]
    original = deepcopy(data)
    provider = FakeProvider([ModelResponse("r1", (), "answer")])
    assert asyncio.run(run_agent(data, provider, ToolAdapters(clock_operation))) == "answer"
    assert data == original
    request = provider.requests[0]
    assert request["model"] == data["model"] and request["previous"] is None
    assert request["input"] == data["history"] + [{"role": "user", "content": "current"}]
    assert len(request["tools"]) == 6
    request["tools"][0]["name"] = "changed"
    assert ToolAdapters(clock_operation).definitions()[0]["name"] != "changed"


@pytest.mark.parametrize(
    "output,expected",
    [
        ([], ""),
        ([{"type": "message", "content": []}], ""),
        ([{"type": "reasoning"}], ""),
        (
            [
                {
                    "type": "message",
                    "content": [
                        {"type": "output_text", "text": "A"},
                        {"type": "refusal", "refusal": "B"},
                    ],
                },
                {"type": "message", "content": [{"type": "output_text", "text": "C"}]},
            ],
            "ABC",
        ),
    ],
)
def test_text_refusal_empty_and_nonmessage_output(output: list[Message], expected: str) -> None:
    response = parse_response({"id": "response", "output": output})
    assert (
        asyncio.run(run_agent(payload(), FakeProvider([response]), ToolAdapters(clock_operation)))
        == expected
    )


def test_ordered_calls_original_ids_and_response_chaining() -> None:
    calls: list[tuple[str, Message]] = []

    async def operation(name: str, value: Message) -> Message:
        calls.append((name, value))
        return {"path": value["path"]} if name == "file.write" else {"content": "read result"}

    provider = FakeProvider(
        [
            ModelResponse(
                "r1",
                (
                    call("writeFile", '{"path":"a","content":"x"}', "original-id"),
                    call("readFile", '{"path":"a"}', "second-id"),
                ),
                "discard intermediate",
            ),
            ModelResponse("r2", (call("readFile", '{"path":"b"}', "third-id"),), "discard too"),
            ModelResponse("r3", (), "final"),
        ]
    )
    assert asyncio.run(run_agent(payload(), provider, ToolAdapters(operation))) == "final"
    assert [name for name, _ in calls] == ["file.write", "file.read", "file.read"]
    assert [request["previous"] for request in provider.requests] == [None, "r1", "r2"]
    assert provider.requests[1]["input"] == [
        {"type": "function_call_output", "call_id": "original-id", "output": "Wrote a"},
        {"type": "function_call_output", "call_id": "second-id", "output": "read result"},
    ]
    assert provider.requests[2]["input"][0]["call_id"] == "third-id"


@pytest.mark.parametrize("text", ["", "last response"])
def test_exactly_five_rounds_returns_last_text_without_sixth_tool(text: str) -> None:
    provider = FakeProvider(
        [ModelResponse(f"r{i}", (call(call_id=f"call-{i}"),), text) for i in range(6)]
    )
    assert asyncio.run(run_agent(payload(), provider, ToolAdapters(clock_operation))) == text
    assert len(provider.requests) == 6


@pytest.mark.parametrize("raw", ["not-json", "null", "[]", '{"goal":3}'])
def test_adapter_shape_failure_does_not_call_java_or_continue(raw: str) -> None:
    provider = FakeProvider([ModelResponse("r", (call("generateWorkoutPlan", raw),), "")])
    with pytest.raises(AgentFailure) as error:
        asyncio.run(run_agent(payload(), provider, ToolAdapters(clock_operation)))
    assert error.value.code == "INVALID_ARGUMENT"
    assert len(provider.requests) == 1


@pytest.mark.parametrize(
    "category,code",
    [("validation", "INVALID_ARGUMENT"), ("storage", "SQL_WRITE"), ("storage", "FILE_READ")],
)
def test_java_failure_stops_later_calls_and_retains_classification(
    category: str, code: str
) -> None:
    error_payload = dict(
        category=category, code=code, message="Java-owned detail", writeOutcome="unknown"
    )
    failure = OperationFailure(error_payload)
    seen: list[str] = []

    async def operation(name: str, value: Message) -> Message:
        seen.append(name)
        if name == "workout_plan.generate_save":
            # Missing fields were passed as null to Java, not rejected by the Python adapter.
            assert value == dict(startDate=None, goal=None, equipment=None)
            raise failure
        return {"text": "clock"}

    provider = FakeProvider([ModelResponse("r", (call(), call("generateWorkoutPlan"), call()), "")])
    with pytest.raises(OperationFailure) as caught:
        asyncio.run(run_agent(payload(), provider, ToolAdapters(operation)))
    assert caught.value is failure
    assert caught.value.payload("none") == error_payload | {"writeOutcome": "none"}
    assert error_payload["writeOutcome"] == failure.error["writeOutcome"] == "unknown"
    assert seen == ["time.current", "workout_plan.generate_save"]
    assert len(provider.requests) == 1


@pytest.mark.parametrize("after_tool", [False, True])
@pytest.mark.parametrize(
    "category,code", [("authentication", "PROVIDER_AUTH"), ("provider", "PROVIDER_FAILURE")]
)
def test_provider_failure_never_replays(after_tool: bool, category: str, code: str) -> None:
    failure = AgentFailure(category, code)
    responses: list[ModelResponse | Exception] = [failure]
    if after_tool:
        responses.insert(0, ModelResponse("r1", (call(),), ""))
    provider = FakeProvider(responses)
    with pytest.raises(AgentFailure) as caught:
        asyncio.run(run_agent(payload(), provider, ToolAdapters(clock_operation)))
    assert caught.value is failure
    assert len(provider.requests) == (2 if after_tool else 1)


def test_unknown_tool_does_not_use_java_or_continue() -> None:
    provider = FakeProvider([ModelResponse("r", (call("unexpected"),), "")])
    with pytest.raises(AgentFailure) as error:
        asyncio.run(run_agent(payload(), provider, ToolAdapters(clock_operation)))
    assert error.value.code == "UNKNOWN_TOOL"
    assert len(provider.requests) == 1


@pytest.mark.parametrize(
    "value",
    [
        {},
        {"id": "r", "output": None},
        {"id": "r", "output": [None]},
        {"id": "r", "output": [{"type": "function_call", "name": 3}]},
        {"id": "r", "output": [{"type": "message", "content": None}]},
    ],
)
def test_malformed_provider_output_is_classified(value: Message) -> None:
    with pytest.raises(AgentFailure) as caught:
        parse_response(value)
    assert caught.value.code == "PROVIDER_FAILURE"


def test_response_tool_bound_rejects_before_any_side_effect() -> None:
    output = [
        dict(type="function_call", name="getCurrentTime", arguments="{}", call_id=str(i))
        for i in range(101)
    ]
    with pytest.raises(AgentFailure) as caught:
        parse_response({"id": "r", "output": output})
    assert caught.value.code == "PROVIDER_FAILURE"


@pytest.mark.parametrize(
    "output",
    [
        [{"type": "reasoning"}] * 1001,
        [{"type": "function_call", "name": "getCurrentTime", "arguments": "{}", "call_id": ""}],
        [{"type": "message", "content": [{"type": "output_text", "text": "\ud800"}]}],
        [{"type": "message", "content": [{"type": []}]}],
    ],
)
def test_invalid_provider_values_are_safe_failures(output: list[Message]) -> None:
    with pytest.raises(AgentFailure) as caught:
        parse_response({"id": "response", "output": output})
    assert caught.value.code == "PROVIDER_FAILURE"
