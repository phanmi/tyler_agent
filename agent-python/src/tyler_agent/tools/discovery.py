"""Discover tool packages shipped with the worker, without global import-time registration."""

import inspect
import json
from importlib import import_module
from importlib.resources import files
from types import ModuleType
from typing import cast

from tyler_agent.tools import builtin
from tyler_agent.tools.contracts import Handler, Tool


def discover_tools(package: ModuleType = builtin) -> tuple[Tool, ...]:
    """The composition boundary supplies a trusted package, never a model-selected path.

    Each immediate child package owns adapter.py and definition.json. Sorting package
    names keeps the provider's tool order deterministic in source and frozen bundles.
    """
    tools: list[Tool] = []
    for resource in sorted(files(package).iterdir(), key=lambda item: item.name):
        if not resource.is_dir() or resource.name.startswith("_"):
            continue
        name = f"{package.__name__}.{resource.name}"
        try:
            definition = json.loads(resource.joinpath("definition.json").read_text("utf-8"))
            adapter = import_module(f"{name}.adapter")
        except (OSError, ValueError, ImportError) as error:
            raise ValueError(f"Cannot load bundled tool package: {name}") from error
        handler = getattr(adapter, "execute", None)
        if not inspect.iscoroutinefunction(handler):
            raise ValueError(f"Bundled tool must expose async execute: {name}")
        tools.append(Tool(definition, cast(Handler, handler)))
    return tuple(tools)
