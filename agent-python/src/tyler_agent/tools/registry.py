"""Validated tool registration and request-scoped dispatch, independent of individual tools."""

import inspect
from collections.abc import Iterable
from copy import deepcopy
from types import MappingProxyType

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import MAX_FRAME_BYTES, Message
from tyler_agent.tools.contracts import Handler, Operation, Tool
from tyler_agent.tools.discovery import discover_tools


class ToolCatalog:
    """Worker-owned catalog, validated once and shared without request-specific state."""

    def __init__(self, tools: Iterable[Tool]) -> None:
        registered: dict[str, Tool] = {}
        for tool in tools:
            name = _validate(tool)
            if name in registered:
                raise ValueError(f"Duplicate tool registration: {name}")
            registered[name] = Tool(deepcopy(tool.definition), tool.execute)
        if not registered:
            raise ValueError("No tools registered")
        self._tools = MappingProxyType(registered)

    def definitions(self) -> list[Message]:
        """Return copies so neither a provider nor another run can mutate the catalog."""
        return [deepcopy(tool.definition) for tool in self._tools.values()]

    def handler(self, name: str) -> Handler:
        tool = self._tools.get(name)
        if tool is None:
            raise AgentFailure("worker", "UNKNOWN_TOOL")
        return tool.execute


class ToolAdapters:
    """Bind the current run's Java capability to a catalog without storing it in handlers."""

    def __init__(
        self, operation: Operation, tools: Iterable[Tool] | ToolCatalog | None = None
    ) -> None:
        self._operation = operation
        self._catalog = (
            tools
            if isinstance(tools, ToolCatalog)
            else ToolCatalog(discover_tools() if tools is None else tools)
        )

    def definitions(self) -> list[Message]:
        return self._catalog.definitions()

    async def execute(self, name: str, raw: str) -> str:
        result = await self._catalog.handler(name)(raw, self._operation)
        if not isinstance(result, str) or len(result.encode("utf-8")) > MAX_FRAME_BYTES:
            raise AgentFailure("worker", "SERIALIZATION_FAILURE")
        return result


def _validate(tool: Tool) -> str:
    """Check registration contracts; model arguments still use each adapter's legacy parser."""
    definition = tool.definition
    if not isinstance(definition, dict):
        raise ValueError("Tool definition must be an object")
    name = definition.get("name")
    if not isinstance(name, str) or not name.strip():
        raise ValueError("Tool definition must have a nonblank name")
    if definition.get("type") != "function" or definition.get("strict") is not True:
        raise ValueError(f"Tool must declare a strict function definition: {name}")
    description, parameters = definition.get("description"), definition.get("parameters")
    if not isinstance(description, str) or not description.strip():
        raise ValueError(f"Tool definition must have a description: {name}")
    if not isinstance(parameters, dict) or parameters.get("type") != "object":
        raise ValueError(f"Tool parameters must declare an object schema: {name}")
    try:
        Draft202012Validator.check_schema(parameters)
    except SchemaError as error:
        raise ValueError(f"Invalid tool parameter schema: {name}") from error
    if not inspect.iscoroutinefunction(tool.execute):
        raise ValueError(f"Tool handler must be asynchronous: {name}")
    try:
        inspect.signature(tool.execute).bind("", None)
    except TypeError as error:
        raise ValueError(
            f"Tool handler must accept raw arguments and an operation: {name}"
        ) from error
    return name
