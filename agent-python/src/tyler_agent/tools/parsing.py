"""Legacy argument shape/coercion, without duplicating Java domain validation."""

import json
import re
from decimal import Decimal, InvalidOperation
from typing import Any

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import MAX_FRAME_BYTES, Message


def _reject_constant(value: str) -> None:
    raise ValueError("Non-finite JSON number")


def arguments(raw: str, category: str = "validation", code: str = "INVALID_ARGUMENT") -> Any:
    """Parse exact decimals; keep parser detail local so arguments cannot leak in errors."""
    try:
        if len(raw.encode("utf-8")) > MAX_FRAME_BYTES:
            raise ValueError("Tool arguments exceed the frame bound")
        return json.loads(
            raw, parse_float=Decimal, parse_int=Decimal, parse_constant=_reject_constant
        )
    except (ValueError, RecursionError, UnicodeError) as error:
        raise AgentFailure(category, code) from error


def object_value(value: Any, *, allowed: set[str] | None = None) -> Message:
    if not isinstance(value, dict) or (allowed is not None and value.keys() - allowed):
        raise AgentFailure("validation", "INVALID_ARGUMENT")
    return value


def text_value(value: Any, *, coerce: bool = False) -> str | None:
    if value is None or isinstance(value, str):
        return value
    if coerce and isinstance(value, (bool, Decimal)):
        return scalar_text(value)
    raise AgentFailure("validation", "INVALID_ARGUMENT")


def scalar_text(value: Any) -> str:
    """Jackson tree asText: containers become empty text; booleans are lowercase."""
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return str(value).lower()
    if isinstance(value, Decimal):
        return str(value)
    return ""


def decimal_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str) and not value.strip():
        return None  # Jackson's empty-string-to-null BigDecimal coercion.
    if isinstance(value, bool) or not isinstance(value, (str, Decimal)):
        raise AgentFailure("validation", "INVALID_ARGUMENT")
    try:
        if isinstance(value, str) and not re.fullmatch(
            r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?", value.strip()
        ):
            raise ValueError("Invalid decimal argument")
        number = Decimal(value.strip() if isinstance(value, str) else value)
        if not number.is_finite() or number.copy_abs() > 2_000_000_000:
            raise ValueError("Decimal outside protocol range")
        # Bound scale before expanding scientific input to avoid allocating enormous strings.
        if abs(int(number.as_tuple().exponent)) > MAX_FRAME_BYTES:
            raise ValueError("Decimal scale exceeds the frame bound")
        return format(number.copy_abs() if number.is_zero() else number, "f")
    except (InvalidOperation, ValueError) as error:
        raise AgentFailure("validation", "INVALID_ARGUMENT") from error


def food_arguments(raw: str) -> Message:
    food = object_value(arguments(raw), allowed={"genericInfo", "macroNutrients"})
    return {
        "food": {
            "genericInfo": _food_part(
                food.get("genericInfo"),
                ("foodName", "amount", "unit", "calories", "date"),
                {"amount", "calories"},
            ),
            "macroNutrients": _food_part(
                food.get("macroNutrients"),
                ("protein", "carbs", "fat", "fiber"),
                {"protein", "carbs", "fat", "fiber"},
            ),
        }
    }


def _food_part(value: Any, fields: tuple[str, ...], decimals: set[str]) -> Message | None:
    if value is None:
        return None
    part = object_value(value, allowed=set(fields))
    return {
        field: decimal_text(part.get(field))
        if field in decimals
        else text_value(part.get(field), coerce=True)
        for field in fields
    }


def plan_arguments(raw: str) -> Message:
    value = object_value(arguments(raw))
    return {field: text_value(value.get(field)) for field in ("startDate", "goal", "equipment")}


def file_arguments(raw: str, *, write: bool) -> Message:
    code = "FILE_WRITE" if write else "FILE_READ"
    value = arguments(raw, "storage", code)
    if not isinstance(value, dict) or value.get("path") is None:
        raise AgentFailure("storage", code)
    path = scalar_text(value["path"])
    if not path.strip():
        raise AgentFailure("storage", code)
    result: Message = {"path": path}
    if write:
        if value.get("content") is None:
            raise AgentFailure("storage", code)
        result["content"] = scalar_text(value["content"])
    return result
