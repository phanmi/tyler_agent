"""Six model adapters sharing a request-scoped, allowlisted Java operation capability."""

import json
from collections.abc import Awaitable, Callable
from importlib.resources import files

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import MAX_FRAME_BYTES, Message
from tyler_agent.tools.parsing import file_arguments, food_arguments, plan_arguments
from tyler_agent.tools.rendering import pretty, profile_text

type Operation = Callable[[str, Message], Awaitable[Message]]


class ToolAdapters:
    def __init__(self, operation: Operation) -> None:
        self._operation = operation
        self._handlers: dict[str, Callable[[str], Awaitable[str]]] = {
            "recordFood": self._food,
            "generateWorkoutPlan": self._plan,
            "GetUserInfo": self._profile,
            "readFile": self._read,
            "writeFile": self._write,
            "getCurrentTime": self._time,
        }

    def definitions(self) -> list[Message]:
        """Return fresh definitions so a provider cannot mutate another run's contracts."""
        result: list[Message] = json.loads(
            files(__package__).joinpath("definitions.json").read_text("utf-8")
        )
        return result

    async def execute(self, name: str, raw: str) -> str:
        handler = self._handlers.get(name)
        if handler is None:
            raise AgentFailure("worker", "UNKNOWN_TOOL")
        result = await handler(raw)
        if len(result.encode("utf-8")) > MAX_FRAME_BYTES:
            raise AgentFailure("worker", "SERIALIZATION_FAILURE")
        return result

    async def _food(self, raw: str) -> str:
        result = await self._operation("food.insert", food_arguments(raw))
        return pretty(result["food"])

    async def _plan(self, raw: str) -> str:
        result = await self._operation("workout_plan.generate_save", plan_arguments(raw))
        return pretty(result["plan"])

    async def _profile(self, raw: str) -> str:
        return profile_text((await self._operation("user_info.read", {}))["profile"])

    async def _read(self, raw: str) -> str:
        result = await self._operation("file.read", file_arguments(raw, write=False))
        return str(result["content"])

    async def _write(self, raw: str) -> str:
        result = await self._operation("file.write", file_arguments(raw, write=True))
        return "Wrote " + str(result["path"])

    async def _time(self, raw: str) -> str:
        return str((await self._operation("time.current", {}))["text"])
