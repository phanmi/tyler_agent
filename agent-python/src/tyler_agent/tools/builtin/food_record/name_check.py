"""The name and count check: are the right foods in the list, in the amounts the user gave?

It runs once for a recordFood call, on every food at once, so it can tell a food that is
missing from one that is there, and a record for a whole meal from the foods it stands for.
It reads names, amounts and units only; dates and nutrition values have their own checks.

The foods cannot be saved without this check, so an answer that cannot be used, from the
check or from one of its corrections, is asked again and then fails the run.
"""

import json
import logging
from dataclasses import dataclass
from functools import partial

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.asking import (
    FLAGGED_FOOD,
    Evidence,
    Question,
    flagged_foods,
    must_ask,
    together,
)
from tyler_agent.tools.builtin.food_record.correction import food_correction
from tyler_agent.tools.builtin.food_record.foods import (
    FoodList,
    portions_text,
    record_text,
    with_portion,
)
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import ArgumentsRejected, arguments, object_value, text_value
from tyler_agent.transcript import ToolDefinition

log = logging.getLogger(__name__)

# One answer may add this many foods at most. A longer list is an unusable answer, so a
# confused model cannot start an unbounded number of correction requests.
MAX_MISSING_FOODS = 20

VERIFY_FOOD_LIST = ToolDefinition(
    "verifyFoodList",
    "Report what is wrong with the list of food diary records for the user's latest message: "
    "foods that are missing, records that do not belong, and records with a wrong amount or "
    "unit.",
    {
        "type": "object",
        "properties": {
            "missing": {
                "type": "array",
                "description": "Each food that has no record in the list, as the user described "
                "it, with its amount. Empty when no food is missing.",
                "items": {"type": "string"},
            },
            "incorrect": {
                "type": "array",
                "description": "Each record that does not belong in the list. Empty when every "
                "record belongs.",
                "items": FLAGGED_FOOD,
            },
            "wrongAmount": {
                "type": "array",
                "description": "Each record whose amount or unit does not match what the user "
                "said. Empty when they all match.",
                "items": FLAGGED_FOOD,
            },
        },
        "required": ["missing", "incorrect", "wrongAmount"],
        "additionalProperties": False,
    },
)

VERIFY_PROMPT = """\
You check the list of food diary records that is about to be saved for the user's latest \
message. Check only which foods are in the list, and their amounts and units. Do not judge \
dates or nutrition values.

The user's latest message can ask to record a food it names, a food described earlier in the \
conversation, or the foods in a tool result it points to, such as a file the user asks to log \
from.

Report three things:
- missing: each food the user's latest message asks to record that has no record in the list. A \
food is not missing when an earlier message already had it recorded, or when an earlier \
recordFood call below already saved it. A food the user only mentions, plans, asks about, or \
says someone else ate is not missing.
- incorrect: each record that is not one food the user's latest message asks to record. That is \
a food the user did not say they ate, a record that stands for a whole meal or for several \
foods at once, a second record for a food the user had once, and a record for a food that an \
earlier recordFood call below already saved.
- wrongAmount: each record whose amount or unit does not match what the user said. A unit \
converted from the user's, such as 240 ml for 1 cup, does not match, and neither does a unit \
with a conversion added, such as "cup (240 ml)". When the user gave two units for one food, \
such as "one 2 oz bar", either matches. When the user gave no amount, a reasonable estimate \
matches.

The conversation, oldest message first. The last message is the user's latest:
{conversation}

The tool calls already made in this chat, oldest first, with their results:
{earlier_calls}

The records to check, numbered:
{foods}

Call {tool} exactly once. Give an empty list where there is nothing to report."""

# What the correct food tool is asked to write; `food` and `reason` are JSON strings.
MISSING_TASK = (
    "The user asked to record this food, and the list that is about to be saved has no record "
    "of it: {food}. Write its record."
)
AMOUNT_TASK = (
    "The amount or unit of the record below does not match what the user said: {reason}. "
    "Write the record again with the amount and unit the user gave, and with nutrition values "
    "for that amount. Keep its name and its date."
)


@dataclass(frozen=True)
class ListVerdict:
    """The check's answer: the missing foods as the user described them, and the reasons for
    the records that do not belong and for those with a wrong amount, keyed by food number."""

    missing: tuple[str, ...]
    incorrect: dict[int, str]
    wrong_amount: dict[int, str]


def list_verdict(count: int, raw: str) -> ListVerdict:
    """Read verifyFoodList's arguments for a list of `count` foods; arguments that do not fit
    the tool, a blank description, or more than MAX_MISSING_FOODS missing foods are rejected."""
    value = object_value(arguments(raw), ("missing", "incorrect", "wrongAmount"))
    missing = value.get("missing")
    if not isinstance(missing, list) or len(missing) > MAX_MISSING_FOODS:
        raise ArgumentsRejected
    described = tuple(text_value(food) or "" for food in missing)
    if not all(food.strip() for food in described):
        raise ArgumentsRejected
    return ListVerdict(
        described,
        flagged_foods(value.get("incorrect"), count),
        flagged_foods(value.get("wrongAmount"), count),
    )


def list_question(evidence: Evidence, foods: FoodList) -> Question[ListVerdict]:
    prompt = VERIFY_PROMPT.format(
        conversation=evidence.conversation,
        earlier_calls=evidence.earlier_calls,
        foods=portions_text(foods),
        tool=VERIFY_FOOD_LIST.name,
    )
    return Question(prompt, VERIFY_FOOD_LIST, partial(list_verdict, len(foods.foods)))


async def check_names(foods: FoodList, evidence: Evidence, context: ToolContext) -> FoodList:
    """Return the list with the records that do not belong removed, wrong amounts corrected
    and missing foods added at the end.

    Every correction is its own request, and they run at the same time. This requests no
    Java operation. Provider failures and cancellation propagate, and so does the failure of
    a check or correction that could not be answered, so nothing is saved after them.
    """
    verdict = await must_ask(context, list_question(evidence, foods))
    # A record that does not belong is removed, so its amount needs no correction.
    amounts = {
        number: food
        for number, food in foods.numbered()
        if number in verdict.wrong_amount and number not in verdict.incorrect
    }
    questions = [
        *(_amount_question(evidence, food, verdict.wrong_amount[n]) for n, food in amounts.items()),
        *(_missing_question(evidence, food) for food in verdict.missing),
    ]
    answers = await together(must_ask(context, question) for question in questions)
    corrected, added = answers[: len(amounts)], answers[len(amounts) :]
    log.info(
        "Food list checked: missing=%d incorrect=%d wrongAmount=%d",
        len(added),
        len(verdict.incorrect),
        len(amounts),
    )
    return foods.revised(
        replaced={
            number: with_portion(food, answer)
            for (number, food), answer in zip(amounts.items(), corrected, strict=True)
        },
        removed=verdict.incorrect,
        added=added,
    )


def _amount_question(evidence: Evidence, food: Message, reason: str) -> Question[Message]:
    task = AMOUNT_TASK.format(reason=json.dumps(reason, ensure_ascii=False))
    return food_correction(evidence, task, record_text(food))


def _missing_question(evidence: Evidence, food: str) -> Question[Message]:
    return food_correction(evidence, MISSING_TASK.format(food=json.dumps(food, ensure_ascii=False)))
