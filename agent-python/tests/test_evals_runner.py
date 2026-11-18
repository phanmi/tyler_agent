"""The eval harness runs a case through the real agent, tools and verification (#97)."""

import asyncio

import pytest
from support.agent_fakes import FakeProvider, call
from support.eval_fakes import (
    ALWAYS,
    KEY,
    NOW,
    USAGE,
    WITH_NUTRITION,
    case,
    chat,
    cleared,
    corrected_food,
    expected,
    food_call,
    list_without,
    nutrition_rejected,
    record,
    session,
    turn,
)

from evals.java import FakeJava
from evals.runner import ChatResult, FoodCheck, ModelCall, run_chat, run_payload
from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.validation import MessageValidator
from tyler_agent.transcript import AssistantTurn, ToolResult, UserTurn


def run(provider: FakeProvider, verify: bool) -> ChatResult:
    return asyncio.run(run_chat(chat(verify), session(provider.open)))


def test_with_the_nutrition_check_off_the_other_two_checks_still_run() -> None:
    provider = FakeProvider([turn(food_call()), *cleared(ALWAYS), turn(text="Recorded.")])

    result = run(provider, verify=False)

    assert result.error is None
    assert result.saved == (record(),)
    assert result.checks == (FoodCheck(2, 0),)
    assert [call.usage for call in result.calls] == [USAGE] * 4


def test_check_requests_are_metered_with_the_chats_own_calls() -> None:
    provider = FakeProvider([turn(food_call()), *cleared(WITH_NUTRITION), turn(text="Recorded.")])

    result = run(provider, verify=True)

    assert result.saved == (record(),)
    assert result.checks == (FoodCheck(3, 0),)
    # The chat's first round, the three checks inside recordFood, and the closing round.
    assert result.calls == (ModelCall(USAGE, 1.0),) * 5


def test_a_corrected_food_counts_its_check_and_correction_requests() -> None:
    provider = FakeProvider(
        [
            turn(food_call(calories=3020)),
            *cleared(ALWAYS),
            turn(nutrition_rejected("3020 kcal is ten times too high.")),
            turn(corrected_food()),
            turn(text="Recorded."),
        ]
    )

    result = run(provider, verify=True)

    assert result.saved == (record(),)
    assert result.checks == (FoodCheck(4, 0),)
    assert len(result.calls) == 6


def test_a_record_the_checks_leave_out_is_not_saved_and_is_counted() -> None:
    provider = FakeProvider(
        [
            turn(food_call()),
            turn(list_without(1, "The user did not eat it.")),
            turn(text="I did not record it."),
        ]
    )

    result = run(provider, verify=True)

    assert result.error is None and result.saved == ()
    # With no food left in the list, the date check and the nutrition check ask nothing.
    assert result.checks == (FoodCheck(1, 1),)


def test_rejected_arguments_are_a_food_check_with_no_model_calls() -> None:
    provider = FakeProvider([turn(call("recordFood", "not json")), turn(text="Sorry.")])

    result = run(provider, verify=True)

    assert result.saved == () and result.checks == (FoodCheck(0, 0),)


def test_the_cases_fixed_time_answers_the_time_tool_and_the_date_check() -> None:
    provider = FakeProvider(
        [
            turn(call("getCurrentTime")),
            turn(food_call()),
            *cleared(WITH_NUTRITION),
            turn(text="Recorded."),
        ]
    )

    run(provider, verify=True)

    time_result = provider.requests[1]["transcript"][-1]
    assert time_result == ToolResult("call-1", NOW)
    date_check = provider.requests[3]["transcript"][0]
    assert isinstance(date_check, UserTurn) and NOW in date_check.text


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


def test_a_chat_that_reads_the_profile_gets_it_through_the_real_tool() -> None:
    provider = FakeProvider([turn(call("GetUserInfo")), turn(text="Hello, Sam.")])

    result = run(provider, verify=False)

    assert result.error is None and result.saved == ()
    profile = provider.requests[1]["transcript"][-1]
    assert profile == ToolResult(
        "call-1",
        "UserInfo[user=User[name=Sam, gender=Unspecified, age=30, jobType=Office], "
        "other=Other[info=null, expectationFromLLM=Keep my food diary accurate.]]",
    )


def test_the_stand_in_profile_is_one_java_could_send() -> None:
    answer = asyncio.run(FakeJava(NOW).operation("user_info.read", {}))

    MessageValidator().validate_shape(
        {
            "protocolVersion": 2,
            "type": "operation_result",
            "sessionId": "0123456789abcdef0123456789abcdef",
            "requestId": "req-1",
            "payload": {
                "operationId": "op-1",
                "operation": "user_info.read",
                "outcome": "success",
                "result": answer,
            },
        }
    )


def test_an_operation_without_a_stand_in_ends_the_chat_as_a_named_error() -> None:
    provider = FakeProvider([turn(call("readFile", '{"path": "notes.txt"}'))])

    result = run(provider, verify=True)

    assert result.error == "unexpected operation: file.read"
    assert len(result.calls) == 1


def test_a_provider_failure_is_an_error_that_keeps_what_was_saved() -> None:
    failure = AgentFailure("provider", "PROVIDER_FAILURE")
    provider = FakeProvider([turn(food_call()), *cleared(ALWAYS), failure])

    result = run(provider, verify=False)

    assert result.error == "provider/PROVIDER_FAILURE"
    assert result.saved == (record(),) and len(result.calls) == 3


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
