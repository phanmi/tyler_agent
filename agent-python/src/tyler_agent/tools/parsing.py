"""Enforce each tool's advertised argument types and fields before any Java operation.

Adapters own only the transport shape. Java services keep every domain rule: required values,
dates, ranges, enums, and sandbox path safety.
"""

import json
from decimal import Decimal
from typing import Any

from tyler_agent.failures import AgentFailure
from tyler_agent.protocol.protocol import MAX_FRAME_BYTES, Message, unique_object
from tyler_agent.protocol.validation import MAX_DECIMAL_VALUE

# Java's Jackson refuses number text over 1000 characters. MAX_DECIMAL_VALUE already limits the
# integer part to ten digits, so this scale keeps every plain form well below that.
MAX_DECIMAL_SCALE = 256


class ArgumentsRejected(Exception):
    """The model's arguments do not match the tool's advertised parameter schema.

    Raise it only while parsing, before requesting any operation. Unlike an AgentFailure it
    does not end the run: the registry answers the model with a fixed result so it can retry.
    """


def _reject_constant(value: str) -> None:
    raise ValueError("Non-finite JSON number")


def arguments(raw: str) -> Any:
    """Parse one JSON value with exact decimals; parser detail stays local, never echoed."""
    try:
        if len(raw.encode("utf-8")) > MAX_FRAME_BYTES:
            raise ValueError("Tool arguments exceed the frame bound")
        # Without the hook a repeated key keeps only its last value, hiding the others from the
        # type checks. An exponent too large for Decimal raises an ArithmeticError.
        return json.loads(
            raw,
            object_pairs_hook=unique_object,
            parse_float=Decimal,
            parse_int=Decimal,
            parse_constant=_reject_constant,
        )
    except (ValueError, ArithmeticError, RecursionError, UnicodeError) as error:
        raise ArgumentsRejected from error


def object_value(value: Any, fields: tuple[str, ...]) -> Message:
    """Require a JSON object with only declared fields, matching additionalProperties: false."""
    if not isinstance(value, dict) or not value.keys() <= set(fields):
        raise ArgumentsRejected
    return value


def _encodable(value: str) -> str:
    """Reject a lone surrogate now; it could not be encoded for the operation request."""
    try:
        value.encode("utf-8")
    except UnicodeError as error:
        raise ArgumentsRejected from error
    return value


def text_value(value: Any) -> str | None:
    """Require a JSON string; null and absence pass to Java, which owns required values."""
    if value is None:
        return None
    if isinstance(value, str):
        return _encodable(value)
    raise ArgumentsRejected


def number_value(value: Any) -> Decimal | None:
    """Require a JSON number; null and absence pass to Java, which owns required values.

    Parsed JSON numbers are Decimal, so booleans and quoted numbers are rejected.
    """
    if value is None or isinstance(value, Decimal):
        return value
    raise ArgumentsRejected


def decimal_text(value: Decimal) -> str:
    """Encode a checked JSON number as an exact plain decimal string.

    A number beyond the protocol bound or the scale limit still matches the schema, so it fails
    the run. The scale is checked before formatting so scientific input cannot expand to an
    enormous string. Zero has no magnitude to bound, so an over-long scale is simply dropped.
    """
    scale_fits = -int(value.as_tuple().exponent) <= MAX_DECIMAL_SCALE
    if value.is_zero():
        return format(value.copy_abs(), "f") if scale_fits else "0"
    if not scale_fits or value.copy_abs() > MAX_DECIMAL_VALUE:
        raise AgentFailure("validation", "INVALID_ARGUMENT")
    return format(value, "f")


GENERIC_INFO_FIELDS = ("foodName", "amount", "unit", "calories", "date")
MACRO_FIELDS = ("protein", "carbs", "fat", "fiber")
PLAN_FIELDS = ("startDate", "goal", "equipment")


def food_arguments(raw: str) -> Message:
    food = object_value(arguments(raw), ("genericInfo", "macroNutrients"))
    # Check every part before encoding either, so a number limit cannot hide a wrong type.
    info = _food_part(food.get("genericInfo"), GENERIC_INFO_FIELDS, {"amount", "calories"})
    macros = _food_part(food.get("macroNutrients"), MACRO_FIELDS, set(MACRO_FIELDS))
    return {"food": {"genericInfo": _encoded(info), "macroNutrients": _encoded(macros)}}


def _food_part(value: Any, fields: tuple[str, ...], decimals: set[str]) -> Message | None:
    """Check one food object's shape and field types without applying number limits."""
    if value is None:
        return None  # The shared food validator reports a missing component.
    part = object_value(value, fields)
    return {
        field: number_value(part.get(field)) if field in decimals else text_value(part.get(field))
        for field in fields
    }


def _encoded(part: Message | None) -> Message | None:
    if part is None:
        return None
    return {
        field: decimal_text(value) if isinstance(value, Decimal) else value
        for field, value in part.items()
    }


def plan_arguments(raw: str) -> Message:
    value = object_value(arguments(raw), PLAN_FIELDS)
    return {field: text_value(value.get(field)) for field in PLAN_FIELDS}


def file_arguments(raw: str, *, write: bool) -> Message:
    """Require string path and content; empty content intentionally writes an empty file.

    A blank path goes back to the model. Unsafe and protected paths are the sandbox's rules, so
    Java rejects them.
    """
    fields = ("path", "content") if write else ("path",)
    value = object_value(arguments(raw), fields)
    if not all(isinstance(value.get(field), str) for field in fields):
        raise ArgumentsRejected
    # str.strip also covers the Unicode spaces that Java's isBlank does not.
    if not value["path"].strip():
        raise ArgumentsRejected
    return {field: _encodable(value[field]) for field in fields}
