"""The date check: is each food recorded on the day the user ate it?

It runs once for a recordFood call, on every food at once, after the name and count check has
settled which foods are in the list. One message can cover several days, so each food's date
is judged on its own. It reads names and dates only.

A wrong date is corrected by a second request, which gives a new date for exactly the foods
the check named. Nothing else in a record changes.

The foods cannot be saved without this check, so an answer that cannot be used, from the
check or from its correction, is asked again and then fails the run.
"""

import logging
from decimal import Decimal
from functools import partial

from tyler_agent.tools.builtin.food_record.asking import (
    FLAGGED_FOOD,
    Evidence,
    Question,
    flagged_foods,
    must_ask,
)
from tyler_agent.tools.builtin.food_record.foods import (
    Change,
    Finding,
    FoodList,
    food_name,
    listed,
    with_date,
)
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import ArgumentsRejected, arguments, object_value, text_value
from tyler_agent.tools.rendering import pretty
from tyler_agent.transcript import ToolDefinition

log = logging.getLogger(__name__)

# What the date check is shown of each food, beside its number.
DATE_FIELDS = ("foodName", "date")

VERIFY_FOOD_DATES = ToolDefinition(
    "verifyFoodDates",
    "Report the food diary records whose date is not the day the user ate the food.",
    {
        "type": "object",
        "properties": {
            "wrong": {
                "type": "array",
                "description": "Each record whose date is wrong. Empty when every date is right.",
                "items": FLAGGED_FOOD,
            },
        },
        "required": ["wrong"],
        "additionalProperties": False,
    },
)

CORRECT_FOOD_DATES = ToolDefinition(
    "correctFoodDates",
    "Give the day the user ate each food whose record has a wrong date.",
    {
        "type": "object",
        "properties": {
            "dates": {
                "type": "array",
                "description": "One entry for each record to correct.",
                "items": {
                    "type": "object",
                    "properties": {
                        "number": {
                            "type": "integer",
                            "description": "The record's number in the list.",
                        },
                        "date": {
                            "type": "string",
                            "description": "The day the user ate the food, in YYYY-MM-DD format.",
                        },
                    },
                    "required": ["number", "date"],
                    "additionalProperties": False,
                },
            },
        },
        "required": ["dates"],
        "additionalProperties": False,
    },
)

# What both prompts say about how a date is worked out, so the check and its correction agree.
DATE_RULE = (
    "A record's date is the day the user ate the food, counted from today's date below. When "
    "the user gave no day for a food, its date is today. A day can also come from a tool result "
    "the user's latest message points to, such as a dated entry in a file the user asks to log "
    "from."
)

VERIFY_PROMPT = """\
You check the dates of the food diary records that are about to be saved for the user's latest \
message. Check only each record's date. Do not judge names, amounts or nutrition values.

{rule}

Report wrong: each record whose date is not the day the user ate that food.

Today's date and time: {today}

The conversation, oldest message first. The last message is the user's latest:
{conversation}

The tool calls already made in this chat, oldest first, with their results:
{earlier_calls}

The records to check, numbered:
{foods}

Call {tool} exactly once. Give an empty list when every date is right."""

CORRECT_PROMPT = """\
You correct the dates of food diary records before they are saved. Each record below has a \
wrong date, with the reason. Give the right date for each one, in YYYY-MM-DD format.

{rule}

Today's date and time: {today}

The conversation, oldest message first. The last message is the user's latest:
{conversation}

The tool calls already made in this chat, oldest first, with their results:
{earlier_calls}

The records to correct, with their numbers:
{foods}

Call {tool} exactly once, with one entry for each record above and no others."""


def wrong_dates(count: int, raw: str) -> dict[int, str]:
    """Read verifyFoodDates' arguments for a list of `count` foods as reasons keyed by food
    number; arguments that do not fit the tool are rejected."""
    return flagged_foods(object_value(arguments(raw), ("wrong",)).get("wrong"), count)


def corrected_dates(wrong: frozenset[int], raw: str) -> dict[int, str]:
    """Read correctFoodDates' arguments as dates keyed by food number.

    The answer must give one date for each food the check named and for no other, so a food
    is never left with a date the check called wrong, and no other food's date changes. Java
    decides whether a date is a real one when the food is saved.
    """
    entries = object_value(arguments(raw), ("dates",)).get("dates")
    if not isinstance(entries, list):
        raise ArgumentsRejected
    dates: dict[int, str] = {}
    for item in entries:
        entry = object_value(item, ("number", "date"))
        number, date = entry.get("number"), text_value(entry.get("date"))
        # Only a number the check named passes, so true is not read as 1, and 1.5 is not
        # rounded to it.
        if not isinstance(number, Decimal) or number not in wrong or int(number) in dates:
            raise ArgumentsRejected
        if date is None or not date.strip():
            raise ArgumentsRejected
        dates[int(number)] = date
    if dates.keys() != wrong:
        raise ArgumentsRejected
    return dates


def dates_question(evidence: Evidence, foods: FoodList) -> Question[dict[int, str]]:
    prompt = VERIFY_PROMPT.format(
        rule=DATE_RULE,
        today=evidence.today,
        conversation=evidence.conversation,
        earlier_calls=evidence.earlier_calls,
        foods=pretty(listed(foods, DATE_FIELDS)),
        tool=VERIFY_FOOD_DATES.name,
    )
    return Question(prompt, VERIFY_FOOD_DATES, partial(wrong_dates, len(foods.foods)))


def correction_question(
    evidence: Evidence, foods: FoodList, wrong: dict[int, str]
) -> Question[dict[int, str]]:
    to_correct = [
        entry | {"reason": wrong[entry["number"]]}
        for entry in listed(foods, DATE_FIELDS)
        if entry["number"] in wrong
    ]
    prompt = CORRECT_PROMPT.format(
        rule=DATE_RULE,
        today=evidence.today,
        conversation=evidence.conversation,
        earlier_calls=evidence.earlier_calls,
        foods=pretty(to_correct),
        tool=CORRECT_FOOD_DATES.name,
    )
    return Question(prompt, CORRECT_FOOD_DATES, partial(corrected_dates, frozenset(wrong)))


async def check_dates(foods: FoodList, evidence: Evidence, context: ToolContext) -> FoodList:
    """Return the list with every wrong date corrected. A list with no foods is not checked.

    This requests no Java operation. Provider failures and cancellation propagate, and so
    does the failure of a check or correction that could not be answered, so nothing is saved
    after them.
    """
    if not foods.foods:
        return foods
    wrong = await must_ask(context, dates_question(evidence, foods))
    log.info("Food dates checked: wrong=%d", len(wrong))
    if not wrong:
        return foods
    dates = await must_ask(context, correction_question(evidence, foods, wrong))
    corrected = {number: food for number, food in foods.numbered() if number in dates}
    check = VERIFY_FOOD_DATES.name
    return foods.revised(
        replaced={number: with_date(food, dates[number]) for number, food in corrected.items()},
        findings=[
            Finding(check, food_name(food), Change.DATE_CORRECTED, wrong[number])
            for number, food in corrected.items()
        ],
    )
