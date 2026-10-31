"""Tool extension, registration failures, and catalog/request isolation."""

import asyncio
import importlib
import json
from copy import deepcopy
from pathlib import Path
from typing import cast

import pytest
from conftest import FIXTURES

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import Message
from tyler_agent.tools import ToolAdapters
from tyler_agent.tools.contracts import Handler, Operation, Tool
from tyler_agent.tools.discovery import discover_tools
from tyler_agent.tools.registry import MALFORMED_CALL_RESULT, ToolCatalog


def test_builtin_definitions_preserve_released_contracts() -> None:
    """The release snapshot replaces parity with the retired Java schema builders."""
    expected = json.loads((FIXTURES / "tool-definitions.json").read_text(encoding="utf-8"))
    actual = ToolAdapters(operation).definitions()
    assert sorted(actual, key=lambda item: item["name"]) == sorted(
        expected, key=lambda item: item["name"]
    )


def definition(name: str = "example") -> Message:
    return {
        "type": "function",
        "name": name,
        "description": "Example tool",
        "strict": True,
        "parameters": {"type": "object", "properties": {}, "additionalProperties": False},
    }


async def execute(raw: str, operation: Operation) -> str:
    return str((await operation("time.current", {}))["text"]) + raw


async def operation(name: str, value: Message) -> Message:
    assert name == "time.current" and value == {}
    return {"text": "clock"}


def test_independent_tool_can_be_registered_without_dispatcher_changes() -> None:
    tools = ToolAdapters(operation, [Tool(definition(), execute)])
    assert asyncio.run(tools.execute("example", "raw")) == "clockraw"
    assert tools.definitions() == [definition()]
    # An unknown model call goes back to the model; the catalog itself still refuses it.
    assert asyncio.run(tools.execute("Example", "")) == MALFORMED_CALL_RESULT
    with pytest.raises(AgentFailure) as error:
        ToolCatalog([Tool(definition(), execute)]).handler("Example")
    assert error.value.code == "UNKNOWN_TOOL"


def test_registry_copies_inputs_and_provider_definitions_and_binds_each_request() -> None:
    value = definition()
    registered = [Tool(value, execute)]
    catalog = ToolCatalog(registered)
    first = ToolAdapters(operation, catalog)

    async def other_operation(name: str, value: Message) -> Message:
        return {"text": "other"}

    second = ToolAdapters(other_operation, catalog)
    value["name"] = "changed"
    value["parameters"]["properties"]["changed"] = {}
    registered.clear()
    exported = first.definitions()
    exported[0]["parameters"]["properties"]["mutated"] = {}
    assert first.definitions() == [definition()]
    assert second.definitions() == [definition()]
    assert asyncio.run(first.execute("example", "")) == "clock"
    assert asyncio.run(second.execute("example", "")) == "other"


@pytest.mark.parametrize(
    "tools,reason",
    [
        ([], "No tools"),
        ([Tool(definition(), execute), Tool(definition(), execute)], "Duplicate tool"),
    ],
)
def test_empty_and_duplicate_registration_fails(tools: list[Tool], reason: str) -> None:
    with pytest.raises(ValueError, match=reason):
        ToolAdapters(operation, tools)


@pytest.mark.parametrize(
    "field,value",
    [
        ("name", None),
        ("name", ""),
        ("name", " "),
        ("type", "other"),
        ("strict", False),
        ("description", ""),
        ("parameters", []),
        ("parameters", {"type": "array"}),
        ("parameters", {"type": "object", "properties": {"broken": {"type": "unknown"}}}),
    ],
)
def test_invalid_definition_fails_before_execution(field: str, value: object) -> None:
    invalid = definition() | {field: value}
    with pytest.raises(ValueError):
        ToolAdapters(operation, [Tool(invalid, execute)])


def test_missing_schema_and_invalid_handler_contracts_are_rejected() -> None:
    invalid = definition()
    del invalid["parameters"]
    with pytest.raises(ValueError, match="parameters"):
        ToolAdapters(operation, [Tool(invalid, execute)])
    with pytest.raises(ValueError, match="asynchronous"):
        ToolAdapters(operation, [Tool(definition(), cast(Handler, lambda raw, op: "sync"))])

    async def wrong_signature() -> str:
        return "wrong"

    with pytest.raises(ValueError, match="raw arguments and an operation"):
        ToolAdapters(operation, [Tool(definition(), cast(Handler, wrong_signature))])


def test_bad_handler_result_is_a_serialization_failure() -> None:
    async def bad_result(raw: str, operation: Operation) -> str:
        return cast(str, None)

    tools = ToolAdapters(operation, [Tool(definition(), bad_result)])
    with pytest.raises(AgentFailure) as error:
        asyncio.run(tools.execute("example", ""))
    assert error.value.code == "SERIALIZATION_FAILURE"


def make_package(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    root = tmp_path / "test_bundled_tools"
    root.mkdir()
    (root / "__init__.py").write_text("", encoding="utf-8")
    monkeypatch.syspath_prepend(str(tmp_path))
    # Every test owns a fresh package; remove earlier imports after its assertions.
    import sys

    for name in list(sys.modules):
        if name == root.name or name.startswith(root.name + "."):
            monkeypatch.delitem(sys.modules, name)
    return root


def add_tool(root: Path, folder: str, name: str) -> Path:
    path = root / folder
    path.mkdir()
    (path / "__init__.py").write_text("", encoding="utf-8")
    (path / "definition.json").write_text(json.dumps(definition(name)), encoding="utf-8")
    (path / "adapter.py").write_text(
        'async def execute(raw, operation):\n    return "example:" + raw\n', encoding="utf-8"
    )
    return path


def test_discovery_picks_up_new_packages_in_deterministic_order(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root = make_package(tmp_path, monkeypatch)
    add_tool(root, "z_tool", "last")
    add_tool(root, "a_tool", "first")
    tools = ToolAdapters(operation, discover_tools(importlib.import_module(root.name)))
    assert [item["name"] for item in tools.definitions()] == ["first", "last"]
    assert asyncio.run(tools.execute("first", "input")) == "example:input"


@pytest.mark.parametrize(
    "broken", ["missing_definition", "invalid_json", "missing_adapter", "missing_handler"]
)
def test_broken_package_does_not_disappear_silently(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, broken: str
) -> None:
    root = make_package(tmp_path, monkeypatch)
    path = add_tool(root, "broken", "example")
    if broken == "missing_definition":
        (path / "definition.json").unlink()
    elif broken == "invalid_json":
        (path / "definition.json").write_text("{", encoding="utf-8")
    elif broken == "missing_adapter":
        (path / "adapter.py").unlink()
    else:
        (path / "adapter.py").write_text("", encoding="utf-8")
    with pytest.raises(ValueError, match="bundled tool|async execute"):
        discover_tools(importlib.import_module(root.name))


def test_builtin_catalog_is_stable_and_has_one_definition_per_adapter() -> None:
    first, second = discover_tools(), discover_tools()
    assert [tool.definition["name"] for tool in first] == [
        "readFile",
        "writeFile",
        "recordFood",
        "getCurrentTime",
        "GetUserInfo",
        "generateWorkoutPlan",
    ]
    original = deepcopy(second[0].definition)
    first[0].definition["name"] = "changed"
    assert second[0].definition == original


def test_invalid_catalog_stops_worker_before_readiness_or_provider(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from tyler_agent import runtime

    sent: list[str] = []
    monkeypatch.setattr(
        runtime,
        "discover_tools",
        lambda: (Tool(definition(), execute), Tool(definition(), execute)),
    )
    with pytest.raises(ValueError, match="Duplicate tool"):
        runtime.WorkerRuntime(lambda kind, request, value: sent.append(kind))
    assert sent == []
