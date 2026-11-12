"""The eval harness runs a case through the real agent, tools and verification (#97)."""

import asyncio

import pytest
from support.agent_fakes import FakeProvider, call
from support.eval_fakes import (
    KEY,
    NOW,
    USAGE,
    case,
    chat,
    expected,
    food_call,
    record,
    session,
    turn,
    verdict,
)

from evals.runner import ChatResult, FoodCheck, ModelCall, run_chat, run_payload
from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.validation import MessageValidator
from tyler_agent.transcript import AssistantTurn, ToolResult, UserTurn


def run(provider: FakeProvider, verify: bool) -> ChatResult:
    return asyncio.run(run_chat(chat(verify), session(provider.open)))


def test_verification_off_saves_through_the_real_tool_with_no_extra_model_calls() -> None:
    provider = FakeProvider([turn(food_call()), turn(text="Recorded.")])

    result = run(provider, verify=False)

    assert result.error is None and result.reply == "Recorded."
    assert result.saved == (record(),)
    assert result.checks == (FoodCheck(0, False),)
    assert [call.usage for call in result.calls] == [USAGE, USAGE]


def test_verification_calls_are_metered_with_the_chats_own_calls() -> None:
    provider = FakeProvider([turn(food_call()), turn(verdict(True)), turn(text="Recorded.")])

    result = run(provider, verify=True)

    assert result.saved == (record(),)
    assert result.checks == (FoodCheck(1, False),)
    # The chat's first round, the verification inside recordFood, and the closing round.
    assert result.calls == (ModelCall(USAGE, 1.0),) * 3


def test_a_regenerated_item_counts_its_verification_and_regeneration_calls() -> None:
    provider = FakeProvider(
        [
            turn(food_call(amount=20)),
            turn(verdict(False, "The user said 200 g.")),
            turn(food_call(amount=200)),
            turn(verdict(True)),
            turn(text="Recorded."),
        ]
    )

    result = run(provider, verify=True)

    assert result.saved == (record(),)
    assert result.checks == (FoodCheck(3, False),)
    assert len(result.calls) == 5


def test_a_refused_item_is_not_saved_and_is_reported_as_refused() -> None:
    provider = FakeProvider(
        [
            turn(food_call(amount=20)),
            turn(verdict(False, "The user said 200 g.")),
            turn(food_call(amount=30)),
            turn(verdict(False, "Still not 200 g.")),
            turn(text="I could not record it."),
        ]
    )

    result = run(provider, verify=True)

    assert result.error is None and result.saved == ()
    assert result.checks == (FoodCheck(3, True),)


def test_rejected_arguments_are_a_food_check_with_no_model_calls() -> None:
    provider = FakeProvider([turn(call("recordFood", "not json")), turn(text="Sorry.")])

    result = run(provider, verify=True)

    assert result.saved == () and result.checks == (FoodCheck(0, False),)


def test_the_cases_fixed_time_answers_the_time_tool_and_the_verification() -> None:
    provider = FakeProvider(
        [
            turn(call("getCurrentTime")),
            turn(food_call()),
            turn(verdict(True)),
            turn(text="Recorded."),
        ]
    )

    run(provider, verify=True)

    time_result = provider.requests[1]["transcript"][-1]
    assert time_result == ToolResult("call-1", NOW)
    verification = provider.requests[2]["transcript"][0]
    assert isinstance(verification, UserTurn) and NOW in verification.text


def test_the_run_payload_is_one_java_could_send() -> None:
    history = ({"role": "user", "content": "Hi"}, {"role": "assistant", "content": "Hello"})
    subject = chat(True, subject=case(expected(), history=history))
    payload = run_payload(subject, KEY)

    validator = MessageValidator()
    message = validator.validate_shape(
        {
            "protocolVersion": 2,
            "type": "run",
            "sessionId": "0123456789abcdef0123456789abcdef",
            "requestId": "req-1",
            "payload": payload,
        }
    )
    validator.validate_semantics(message)

    assert payload["history"] == list(history)
    assert (payload["provider"], payload["model"]) == ("openai", "configured-model")


def test_the_provider_gets_the_key_and_the_result_never_holds_it() -> None:
    provider = FakeProvider([turn(text="Hello.")])

    result = run(provider, verify=True)

    assert provider.snapshots[0]["apiKey"] == KEY and provider.closed == 1
    assert KEY not in repr(result)


def test_an_operation_without_a_stand_in_ends_the_chat_as_a_named_error() -> None:
    provider = FakeProvider([turn(call("readFile", '{"path": "notes.txt"}'))])

    result = run(provider, verify=True)

    assert result.error == "unexpected operation: file.read"
    assert len(result.calls) == 1


def test_a_provider_failure_is_an_error_that_keeps_what_was_saved() -> None:
    provider = FakeProvider([turn(food_call()), AgentFailure("provider", "PROVIDER_FAILURE")])

    result = run(provider, verify=False)

    assert result.error == "provider/PROVIDER_FAILURE"
    assert result.saved == (record(),) and len(result.calls) == 1


def test_an_unexpected_exception_is_an_error_without_its_text() -> None:
    provider = FakeProvider([RuntimeError("secret request text")])

    result = run(provider, verify=True)

    assert result.error == "internal: RuntimeError"
    assert result.calls == () and result.checks == ()


def test_a_chat_that_exceeds_its_deadline_is_a_timeout_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Stalled(FakeProvider):
        async def respond(self, *arguments: object) -> AssistantTurn:
            await asyncio.Event().wait()
            raise AssertionError("unreachable")

    monkeypatch.setattr("evals.runner.CHAT_TIMEOUT_MS", 1)

    result = run(Stalled([]), verify=True)

    assert result.error == "timeout"
