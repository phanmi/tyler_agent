"""Model-facing legacy output formats; wire decimals stay strings until this boundary."""

import json
import os
from decimal import Decimal
from typing import Any

from tyler_agent.protocol.protocol import Message

DECIMALS = {"amount", "calories", "protein", "carbs", "fat", "fiber", "weight"}
RECORD_FIELDS = (
    ("genericInfo", "macroNutrients"),
    ("foodName", "amount", "unit", "calories", "date"),
    ("protein", "carbs", "fat", "fiber"),
    ("startDate", "goal", "equipment", "weightGuidance", "days"),
    ("date", "focus", "workouts", "activity", "durationMinutes"),
    ("workoutName", "rep", "weight", "workoutDate"),
)


def pretty(value: Any, depth: int = 0, field: str = "") -> str:
    """Match Jackson's default pretty printer, including inline arrays and decimal tokens."""
    if isinstance(value, dict):
        if not value:
            return "{ }"
        # Wire object order is irrelevant; legacy output uses Java record component order.
        keys = next((order for order in RECORD_FIELDS if set(order) == value.keys()), tuple(value))
        entries = [
            "  " * (depth + 1)
            + json.dumps(key, ensure_ascii=False)
            + " : "
            + pretty(value[key], depth + 1, key)
            for key in keys
        ]
        return "{" + os.linesep + ("," + os.linesep).join(entries) + os.linesep + "  " * depth + "}"
    if isinstance(value, list):
        return "[ " + ", ".join(pretty(item, depth) for item in value) + " ]" if value else "[ ]"
    if field in DECIMALS and value is not None:
        return str(Decimal(value))
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def profile_text(profile: Message) -> str:
    """Preserve Java record component names/order, unquoted strings, and null rendering."""
    user = _record(
        "User",
        profile["User"],
        (("name", "Name"), ("gender", "Gender"), ("age", "Age"), ("jobType", "JobType")),
    )
    other = _record(
        "Other", profile["Other"], (("info", "Info"), ("expectationFromLLM", "expectationFromLLM"))
    )
    return f"UserInfo[user={user}, other={other}]"


def _record(name: str, value: Message | None, fields: tuple[tuple[str, str], ...]) -> str:
    if value is None:
        return "null"
    return (
        name
        + "["
        + ", ".join(
            f"{component}={value[key] if value[key] is not None else 'null'}"
            for component, key in fields
        )
        + "]"
    )
