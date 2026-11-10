"""Tool shape, decimal, formatting, and service-boundary compatibility."""

import asyncio
import json
from collections.abc import Iterator
from copy import deepcopy
from decimal import Decimal

import pytest
from conftest import FIXTURES, sequence_message
from support.agent_fakes import fake_context

from tyler_agent.failures import AgentFailure, OperationFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.discovery import discover_tools
from tyler_agent.tools.parsing import file_arguments, food_arguments
from tyler_agent.tools.registry import MALFORMED_CALL_RESULT, ToolCatalog
from tyler_agent.tools.rendering import DECIMALS, pretty, profile_text


def model_json(value: object, field: str = "") -> str:
    """Write a wire input as model arguments: wire decimals are strings, model ones are numbers.

    The decimal text is emitted as the number token itself, so its exact digits are preserved.
    """
    if isinstance(value, dict):
        return (
            "{" + ",".join(json.dumps(k) + ":" + model_json(v, k) for k, v in value.items()) + "}"
        )
    if field in DECIMALS and isinstance(value, str):
        return value
    return json.dumps(value)


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
    output = asyncio.run(ToolAdapters(fake_context(operation)).execute(name, model_json(raw)))
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


# One schema-valid call per tool that takes arguments; each case below changes one part.
VALID: dict[str, Message] = {
    "readFile": {"path": "notes.txt"},
    "writeFile": {"path": "notes.txt", "content": "retain-me"},
    "recordFood": {
        "genericInfo": {
            "foodName": "Oats",
            "amount": 100,
            "unit": "g",
            "calories": 389,
            "date": "2026-10-20",
        },
        "macroNutrients": {"protein": 17, "carbs": 66, "fat": 7, "fiber": 10},
    },
    "generateWorkoutPlan": {"startDate": "2026-10-20", "goal": "strength", "equipment": "gym"},
}
# Validated once: the catalog is the same for every case below.
CATALOG = ToolCatalog(discover_tools())
DEFINITIONS = {definition["name"]: definition for definition in CATALOG.definitions()}
# Values of every other JSON type, including quoted numbers and booleans, per declared type.
WRONG_TYPES: dict[str, tuple[object, ...]] = {
    "string": (1, 1.5, True, {}, []),
    "number": ("1", "", True, {}, []),
    "object": ("{}", 1, False, []),
}


def wrong_values(field: Message) -> tuple[object, ...]:
    """Tolerate an unsupported type here, so the definitions test can report it by name."""
    declared = field["type"]
    return WRONG_TYPES.get(declared, ()) if isinstance(declared, str) else ()


def objects(
    schema: Message, path: tuple[str, ...] = ()
) -> Iterator[tuple[tuple[str, ...], Message]]:
    """Yield each object schema in a tool definition with its path in the arguments."""
    yield path, schema
    for name, field in schema["properties"].items():
        if field["type"] == "object":
            yield from objects(field, path + (name,))


def replaced(value: Message, path: tuple[str, ...], replacement: object) -> Message:
    copy = deepcopy(value)
    target = copy
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = replacement
    return copy


def recorded(result: Message | None = None) -> tuple[ToolAdapters, list[tuple[str, Message]]]:
    """Adapters whose Java operation only records requests, so no request means no effect."""
    seen: list[tuple[str, Message]] = []

    async def operation(name: str, value: Message) -> Message:
        seen.append((name, value))
        return result or {"content": "", "path": "", "food": {}, "plan": {}}

    return ToolAdapters(fake_context(operation), CATALOG), seen


def rejected(name: str, raw: str) -> None:
    tools, seen = recorded()
    assert asyncio.run(tools.execute(name, raw)) == MALFORMED_CALL_RESULT
    assert seen == []


@pytest.mark.parametrize("name", VALID)
def test_definitions_are_strict_and_a_valid_call_delegates_once(name: str) -> None:
    for path, schema in objects(DEFINITIONS[name]["parameters"]):
        properties = schema["properties"]
        assert schema["type"] == "object"
        assert schema["additionalProperties"] is False
        assert schema["required"] == list(properties)
        assert [key for key, field in properties.items() if not wrong_values(field)] == []
        # The valid call uses every declared field, so a definition cannot drift from the parser.
        valid = VALID[name]
        for key in path:
            valid = valid[key]
        assert valid.keys() == properties.keys()
    tools, seen = recorded()
    asyncio.run(tools.execute(name, json.dumps(VALID[name])))
    assert len(seen) == 1


@pytest.mark.parametrize(
    "name,path,wrong",
    [
        pytest.param(name, path + (field,), wrong, id=f"{name}.{'.'.join(path + (field,))}")
        for name in VALID
        for path, schema in objects(DEFINITIONS[name]["parameters"])
        for field, declared in schema["properties"].items()
        for wrong in wrong_values(declared)
    ],
)
def test_each_declared_field_rejects_other_json_types(
    name: str, path: tuple[str, ...], wrong: object
) -> None:
    rejected(name, json.dumps(replaced(VALID[name], path, wrong)))


@pytest.mark.parametrize(
    "name,path",
    [
        pytest.param(name, path, id=f"{name}:{'.'.join(path) or 'root'}")
        for name in VALID
        for path, _ in objects(DEFINITIONS[name]["parameters"])
    ],
)
def test_each_object_rejects_unsupported_properties(name: str, path: tuple[str, ...]) -> None:
    rejected(name, json.dumps(replaced(VALID[name], path + ("unexpected",), True)))


def test_plan_preview_is_an_unsupported_property() -> None:
    rejected("generateWorkoutPlan", json.dumps(VALID["generateWorkoutPlan"] | {"preview": True}))


@pytest.mark.parametrize("raw", ["", "{", "null", "[]", '"notes.txt"', "1", "true", "NaN"])
@pytest.mark.parametrize("name", VALID)
def test_malformed_json_and_non_object_roots_are_rejected(name: str, raw: str) -> None:
    rejected(name, raw)


@pytest.mark.parametrize("name", VALID)
def test_trailing_tokens_after_a_valid_call_are_rejected(name: str) -> None:
    rejected(name, json.dumps(VALID[name]) + " x")


@pytest.mark.parametrize(
    "name,raw",
    [
        ("readFile", "{}"),
        ("readFile", '{"path":null}'),
        ("writeFile", "{}"),
        ("writeFile", '{"path":"notes.txt"}'),
        ("writeFile", '{"content":"x"}'),
        ("writeFile", '{"path":null,"content":"x"}'),
        ("writeFile", '{"path":"notes.txt","content":null}'),
    ],
)
def test_missing_or_null_file_fields_are_rejected(name: str, raw: str) -> None:
    rejected(name, raw)


@pytest.mark.parametrize(
    "name,raw",
    [
        ("writeFile", '{"path":"notes.txt","content":{"x":1},"content":""}'),
        ("writeFile", '{"path":"notes.txt","content":"a","content":"a"}'),
        ("readFile", '{"path":1,"path":"notes.txt"}'),
        ("generateWorkoutPlan", '{"goal":1,"goal":"x","startDate":"2026-01-05","equipment":"y"}'),
        ("recordFood", '{"genericInfo":{"amount":1,"amount":2},"macroNutrients":{}}'),
    ],
)
def test_repeated_keys_are_rejected(name: str, raw: str) -> None:
    rejected(name, raw)


@pytest.mark.parametrize(
    "raw",
    [
        '{"path":1e-9999999999999999999999999}',
        '{"path":1e9999999999999999999999999}',
        '{"path":"notes.txt","extra":1e-9999999999999999999999999}',
    ],
)
def test_exponents_outside_the_decimal_range_are_rejected(raw: str) -> None:
    rejected("readFile", raw)


@pytest.mark.parametrize(
    "name,raw",
    [
        ("readFile", r'{"path":"\ud800"}'),
        ("writeFile", r'{"path":"\ud800","content":"x"}'),
        ("writeFile", r'{"path":"a.txt","content":"\ud800"}'),
        ("writeFile", r'{"path":"a.txt","content":"half \ude00"}'),
        ("recordFood", r'{"genericInfo":{"foodName":"\ud800"}}'),
        ("generateWorkoutPlan", r'{"startDate":"\ud800"}'),
    ],
)
def test_lone_surrogates_are_rejected(name: str, raw: str) -> None:
    rejected(name, raw)


def test_paired_surrogate_escapes_are_valid_text() -> None:
    raw = r'{"path":"a.txt","content":"🙂"}'
    assert file_arguments(raw, write=True)["content"] == "🙂"


@pytest.mark.parametrize("path", ["", " ", "\t", " ", " ", " ", "\u0085"])
@pytest.mark.parametrize("name,extra", [("readFile", {}), ("writeFile", {"content": "x"})])
def test_blank_paths_are_rejected(name: str, extra: Message, path: str) -> None:
    rejected(name, json.dumps({"path": path} | extra))


@pytest.mark.parametrize(
    "raw",
    [
        '{"genericInfo":{"amount":3000000000,"unit":true}}',
        '{"genericInfo":{"amount":3000000000},"macroNutrients":"oops"}',
        '{"genericInfo":{"foodName":5,"amount":3000000000}}',
    ],
)
def test_wrong_types_are_rejected_before_number_limits_apply(raw: str) -> None:
    rejected("recordFood", raw)


@pytest.mark.parametrize(
    "path,content",
    [
        ("notes.txt", ""),
        ("notes/2026/10/session.txt", "line\r\nnext"),
        ("笔记/日记 ✓.txt", "héllo 🙂"),
    ],
)
def test_file_strings_reach_java_unchanged(path: str, content: str) -> None:
    tools, seen = recorded({"path": path, "content": content})
    write = json.dumps({"path": path, "content": content}, ensure_ascii=False)
    assert asyncio.run(tools.execute("writeFile", write)) == "Wrote " + path
    assert asyncio.run(tools.execute("readFile", json.dumps({"path": path}))) == content
    assert seen == [
        ("file.write", {"path": path, "content": content}),
        ("file.read", {"path": path}),
    ]


def test_missing_and_null_domain_values_still_reach_java_validation() -> None:
    tools, seen = recorded()
    plan = '{"startDate":"+999999999-12-31","goal":"","equipment":null}'
    asyncio.run(tools.execute("generateWorkoutPlan", plan))
    food = '{"genericInfo":{"foodName":"","amount":null,"date":"not-a-date"}}'
    asyncio.run(tools.execute("recordFood", food))
    assert seen == [
        (
            "workout_plan.generate_save",
            {"startDate": "+999999999-12-31", "goal": "", "equipment": None},
        ),
        (
            "food.insert",
            {
                "food": {
                    "genericInfo": {
                        "foodName": "",
                        "amount": None,
                        "unit": None,
                        "calories": None,
                        "date": "not-a-date",
                    },
                    "macroNutrients": None,
                }
            },
        ),
    ]


@pytest.mark.parametrize(
    "number,expected",
    [
        ("0", "0"),
        ("-0.00", "0.00"),
        ("-1.5", "-1.5"),
        ("16.900", "16.900"),
        ("1E+3", "1000"),
        ("0.00000000000000000100", "0.00000000000000000100"),
        ("null", None),
        ("2000000000.000", "2000000000.000"),
        ("1e-256", "0." + "0" * 255 + "1"),
        ("0e20000000", "0"),
        ("0e-20000000", "0"),
        ("-0e-20000000", "0"),
    ],
)
def test_food_numbers_keep_exact_decimal_text(number: str, expected: str | None) -> None:
    food = food_arguments('{"genericInfo":{"amount":' + number + "}}")["food"]
    assert food["genericInfo"]["amount"] == expected
    assert food["genericInfo"]["date"] is None and food["macroNutrients"] is None


@pytest.mark.parametrize(
    "value",
    [
        "2000000001",
        "-2000000001",
        "1e999999999",
        "1e-99999999",
        "2000000000.000000000000000000000000001",
        "1e-257",
        "1e-999",
        "1e-16777216",
        "0." + "0" * 256 + "1",
    ],
)
def test_numbers_beyond_the_protocol_bound_still_end_the_run(value: str) -> None:
    tools, seen = recorded()
    with pytest.raises(AgentFailure) as caught:
        asyncio.run(tools.execute("recordFood", '{"genericInfo":{"amount":' + value + "}}"))
    assert (caught.value.category, caught.value.code) == ("validation", "INVALID_ARGUMENT")
    assert seen == []


@pytest.mark.parametrize(
    "name,category,code",
    [
        ("readFile", "storage", "FILE_READ"),
        ("writeFile", "storage", "FILE_WRITE"),
        ("recordFood", "validation", "SQL_DATA_VALIDATION"),
        ("generateWorkoutPlan", "validation", "INVALID_ARGUMENT"),
    ],
)
def test_java_storage_and_domain_failures_still_end_the_run(
    name: str, category: str, code: str
) -> None:
    """Only argument shape goes back to the model; Java's failures keep their classification."""
    error = {"category": category, "code": code, "message": "Java-owned detail"}

    async def operation(operation_name: str, value: Message) -> Message:
        raise OperationFailure(error)

    with pytest.raises(OperationFailure) as caught:
        asyncio.run(ToolAdapters(fake_context(operation)).execute(name, json.dumps(VALID[name])))
    assert caught.value.error == error


@pytest.mark.parametrize("name", ["getCurrentTime", "GetUserInfo"])
def test_noargument_tools_ignore_malformed_arguments(name: str) -> None:
    async def operation(operation_name: str, value: Message) -> Message:
        assert value == {}
        return {"text": "clock", "profile": {"User": None, "Other": None}}

    result = asyncio.run(ToolAdapters(fake_context(operation)).execute(name, "not json"))
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
