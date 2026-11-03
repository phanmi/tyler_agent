"""Enforce each tool's advertised argument types and fields before any Java operation.

Adapters own only the transport shape. Java services keep every domain rule: required values,
dates, ranges, enums, and sandbox path checks.
"""

import json
from decimal import Decimal
from typing import Any

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import MAX_FRAME_BYTES, Message
from tyler_agent.protocol.validation import MAX_DECIMAL_VALUE


class ArgumentsRejected(Exception):
    """The model's arguments do not match the tool's advertised parameter schema.

    Raised only while parsing, before any operation is requested. Unlike an AgentFailure it
    does not end the run: the registry answers the model with a fixed result so it can retry.
    """


def _reject_constant(value: str) -> None:
    raise ValueError("Non-finite JSON number")


def arguments(raw: str) -> Any:
    """Parse one JSON value with exact decimals; parser detail stays local, never echoed."""
    try:
        if len(raw.encode("utf-8")) > MAX_FRAME_BYTES:
            raise ValueError("Tool arguments exceed the frame bound")
        return json.loads(
            raw, parse_float=Decimal, parse_int=Decimal, parse_constant=_reject_constant
        )
    except (ValueError, RecursionError, UnicodeError) as error:
        raise ArgumentsRejected from error


def object_value(value: Any, fields: tuple[str, ...]) -> Message:
    """Require a JSON object with only declared fields, matching additionalProperties: false."""
    if not isinstance(value, dict) or not value.keys() <= set(fields):
        raise ArgumentsRejected
    return value


def text_value(value: Any) -> str | None:
    """Require a JSON string; null and absence pass to Java, which owns required values."""
    if value is None or isinstance(value, str):
        return value
    raise ArgumentsRejected


def decimal_text(value: Any) -> str | None:
    """Require a JSON number and encode it as an exact plain decimal string.

    Booleans and quoted numbers are rejected. Null and absence pass to Java validation.
    """
    if value is None:
        return None
    # Parsed JSON numbers are Decimal; bool is not a Decimal subclass.
    if not isinstance(value, Decimal):
        raise ArgumentsRejected
    # A number beyond the protocol bound still matches the schema, so it fails the run.
    # Bound the scale before formatting so scientific input cannot expand to an enormous string.
    if (
        not value.is_finite()
        or value.copy_abs() > MAX_DECIMAL_VALUE
        or abs(int(value.as_tuple().exponent)) > MAX_FRAME_BYTES
    ):
        raise AgentFailure("validation", "INVALID_ARGUMENT")
    return format(value.copy_abs() if value.is_zero() else value, "f")


GENERIC_INFO_FIELDS = ("foodName", "amount", "unit", "calories", "date")
MACRO_FIELDS = ("protein", "carbs", "fat", "fiber")
PLAN_FIELDS = ("startDate", "goal", "equipment")


def food_arguments(raw: str) -> Message:
    food = object_value(arguments(raw), ("genericInfo", "macroNutrients"))
    return {
        "food": {
            "genericInfo": _food_part(
                food.get("genericInfo"), GENERIC_INFO_FIELDS, {"amount", "calories"}
            ),
            "macroNutrients": _food_part(
                food.get("macroNutrients"), MACRO_FIELDS, set(MACRO_FIELDS)
            ),
        }
    }


def _food_part(value: Any, fields: tuple[str, ...], decimals: set[str]) -> Message | None:
    if value is None:
        return None  # The shared food validator reports a missing component.
    part = object_value(value, fields)
    return {
        field: decimal_text(part.get(field)) if field in decimals else text_value(part.get(field))
        for field in fields
    }


def plan_arguments(raw: str) -> Message:
    value = object_value(arguments(raw), PLAN_FIELDS)
    return {field: text_value(value.get(field)) for field in PLAN_FIELDS}


def file_arguments(raw: str, *, write: bool) -> Message:
    """Require string path and content; empty content intentionally writes an empty file.

    Blank, unsafe, and protected paths are the sandbox's rules, so Java rejects them.
    """
    fields = ("path", "content") if write else ("path",)
    value = object_value(arguments(raw), fields)
    if not all(isinstance(value.get(field), str) for field in fields):
        raise ArgumentsRejected
    return {field: value[field] for field in fields}
