"""Tool shape, decimal, formatting, and service-boundary compatibility."""

import asyncio
import json
from decimal import Decimal

import pytest
from conftest import FIXTURES, sequence_message

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.parsing import file_arguments, food_arguments, plan_arguments
from tyler_agent.tools.rendering import pretty, profile_text


@pytest.mark.parametrize(
    "prefix,name,field",
    [
        ("food", "recordFood", "food"),
        ("plan", "generateWorkoutPlan", "plan"),
        ("profile", "GetUserInfo", "profile"),
        ("file_read", "readFile", "content"),
        ("file_write", "writeFile", "path"),
        ("time", "getCurrentTime", "text"),
    ],
)
def test_all_six_adapters_use_private_operations(prefix: str, name: str, field: str) -> None:
    request = sequence_message({"ref": prefix + "_request"})["payload"]
    result: Message = sequence_message({"ref": prefix + "_result"})["payload"]["result"]
    seen: list[tuple[str, Message]] = []

    async def operation(operation_name: str, value: Message) -> Message:
        seen.append((operation_name, value))
        return result

    raw = request["input"]["food"] if prefix == "food" else request["input"]
    output = asyncio.run(ToolAdapters(operation).execute(name, json.dumps(raw)))
    assert seen == [(request["operation"], request["input"])]
    if prefix in {"food", "plan"}:
        assert (
            json.loads(output, parse_float=Decimal)[
                "startDate" if prefix == "plan" else "genericInfo"
            ]
            is not None
        )
        assert '"id"' not in output
    elif prefix == "profile":
        assert output == profile_text(result[field])
    else:
        assert output == ("Wrote " if prefix == "file_write" else "") + result[field]
    if prefix in {"food", "plan", "profile"}:
        golden = json.loads((FIXTURES / "tool-output.json").read_text("utf-8"))
        assert output.replace("\r\n", "\n") == golden[prefix]


@pytest.mark.parametrize(
    "raw",
    [
        "null",
        "[]",
        "true",
        "{",
        '{"extra":1}',
        '{"genericInfo":{"extra":1}}',
        '{"genericInfo":{"amount":true}}',
        '{"genericInfo":{"foodName":[]}}',
    ],
)
def test_food_shape_rejection(raw: str) -> None:
    with pytest.raises(AgentFailure) as caught:
        food_arguments(raw)
    assert caught.value.code == "INVALID_ARGUMENT"


@pytest.mark.parametrize(
    "number,expected",
    [
        ("0", "0"),
        ("-0.00", "0.00"),
        ("-1.5", "-1.5"),
        ("16.900", "16.900"),
        ('"1E+3"', "1000"),
        ('"0.00000000000000000100"', "0.00000000000000000100"),
        ('" "', None),
        ("null", None),
        ("2000000000.000", "2000000000.000"),
    ],
)
def test_food_decimals_and_missing_fields_reach_java(number: str, expected: str | None) -> None:
    food = food_arguments('{"genericInfo":{"amount":' + number + "}}")["food"]
    assert food["genericInfo"]["amount"] == expected
    assert food["genericInfo"]["date"] is None and food["macroNutrients"] is None


@pytest.mark.parametrize(
    "value",
    [
        "NaN",
        "Infinity",
        '"NaN"',
        '"1e999999999"',
        '"1_000"',
        "2000000001",
        '"2000000000.000000000000000000000000001"',
        "true",
    ],
)
def test_unrepresentable_decimal_fails_before_dispatch(value: str) -> None:
    with pytest.raises(AgentFailure):
        food_arguments('{"genericInfo":{"amount":' + value + "}}")


def test_plan_ignores_extra_fields_and_preserves_missing_invalid_domain_values() -> None:
    assert plan_arguments('{"extra":1,"startDate":"+999999999-12-31","goal":""}') == {
        "startDate": "+999999999-12-31",
        "goal": "",
        "equipment": None,
    }
    food = food_arguments('{"genericInfo":{"foodName":42,"unit":true,"date":"not-a-date"}}')["food"]
    assert food["genericInfo"]["foodName"] == "42"
    assert food["genericInfo"]["unit"] == "true"
    assert food["genericInfo"]["date"] == "not-a-date"


@pytest.mark.parametrize(
    "raw,expected",
    [
        ('{"path":3,"content":true}', {"path": "3", "content": "true"}),
        ('{"path":"x","content":[]}', {"path": "x", "content": ""}),
        ('{"path":"x","content":"","extra":1}', {"path": "x", "content": ""}),
    ],
)
def test_file_scalar_coercion_and_empty_content(raw: str, expected: Message) -> None:
    assert file_arguments(raw, write=True) == expected


@pytest.mark.parametrize(
    "raw", ["{", "null", "[]", "{}", '{"path":" "}', '{"path":[]}', '{"path":null}']
)
@pytest.mark.parametrize("write", [False, True])
def test_file_failures_remain_storage_errors(raw: str, write: bool) -> None:
    with pytest.raises(AgentFailure) as caught:
        file_arguments(raw, write=write)
    assert caught.value.category == "storage"
    assert caught.value.code == ("FILE_WRITE" if write else "FILE_READ")


@pytest.mark.parametrize("raw", ['{"path":"x"}', '{"path":"x","content":null}'])
def test_missing_file_content(raw: str) -> None:
    with pytest.raises(AgentFailure) as caught:
        file_arguments(raw, write=True)
    assert caught.value.code == "FILE_WRITE"


@pytest.mark.parametrize("name", ["getCurrentTime", "GetUserInfo"])
def test_noargument_tools_ignore_malformed_arguments(name: str) -> None:
    async def operation(operation_name: str, value: Message) -> Message:
        assert value == {}
        return {"text": "clock", "profile": {"User": None, "Other": None}}

    result = asyncio.run(ToolAdapters(operation).execute(name, "not json"))
    assert result in {"clock", "UserInfo[user=null, other=null]"}


def test_pretty_output_exact_numbers_nulls_and_inline_arrays() -> None:
    value = {
        "days": [{"weight": "16.900", "activity": None, "workouts": []}],
        "amount": "0.0000001",
    }
    assert pretty(value).replace("\r\n", "\n") == (
        '{\n  "days" : [ {\n    "weight" : 16.900,\n    "activity" : null,\n'
        '    "workouts" : [ ]\n  } ],\n  "amount" : 1E-7\n}'
    )


def test_profile_component_order_unquoted_strings_and_nulls() -> None:
    assert profile_text(
        {
            "User": {"Name": "A\nB", "Gender": None, "Age": "16.00", "JobType": "desk"},
            "Other": {"Info": "", "expectationFromLLM": "x"},
        }
    ) == (
        "UserInfo[user=User[name=A\nB, gender=null, age=16.00, jobType=desk], "
        "other=Other[info=, expectationFromLLM=x]]"
    )
