"""The content check: are a food's nutrition values plausible for that food and amount?

It runs once for each food of a recordFood call, all at the same time, after the name and
count check and the date check. It is shown one food and nothing else: no conversation, no
tool results and no date. So it judges the values against what is known about the food, and
cannot be swayed by the chat.

It cannot tell a value the user stated from an estimate, so it may reject a stated value that
looks unusual. The correction that follows is shown the conversation and keeps every value
the user stated; only the nutrition values of its answer are kept, and they are saved without
a second check.

The foods can be saved without this check, so the user can switch it off, and an answer that
cannot be used is asked once more and then the food goes on with the values it has.
"""

import json
import logging
from dataclasses import dataclass

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.builtin.food_record.asking import (
    OPTIONAL_ATTEMPTS,
    Evidence,
    Question,
    ask,
    together,
)
from tyler_agent.tools.builtin.food_record.correction import food_correction
from tyler_agent.tools.builtin.food_record.foods import (
    FoodList,
    nutrition_text,
    record_text,
    with_nutrition,
)
from tyler_agent.tools.contracts import ToolContext
from tyler_agent.tools.parsing import ArgumentsRejected, arguments, object_value, text_value
from tyler_agent.transcript import ToolDefinition

log = logging.getLogger(__name__)

# Where values the user did not state come from, so the check judges a record against the
# source it was meant to use. recordFood's description gives the chat model, and the correct
# food tool that repeats it, the same sources in its own words.
NUTRITION_SOURCES = (
    "the brand's published nutrition for a packaged or restaurant food, and, for any other food "
    "or when the brand's values are not known, USDA FoodData Central values for that food or "
    "for the closest generic food in FoodData Central"
)

VERIFY_FOOD_NUTRITION = ToolDefinition(
    "verifyFoodNutrition",
    "Report whether the nutrition values of a food diary record are plausible for that food "
    "and amount.",
    {
        "type": "object",
        "properties": {
            "valid": {
                "type": "boolean",
                "description": "True only if every nutrition value is plausible.",
            },
            "reason": {
                "type": "string",
                "description": "One or two sentences. When rejecting, say which value is wrong "
                "and what it should be.",
            },
        },
        "required": ["valid", "reason"],
        "additionalProperties": False,
    },
)

VERIFY_PROMPT = """\
You check the nutrition values of one food diary record before it is saved. You are shown only \
the record.

Reject the record if its calories, protein, carbs, fat or fiber is implausible for that food \
and that amount in its unit, or inconsistent with the other values. Judge them against \
{sources}. Do not reject a value for differing slightly.

Do not judge the food's name, amount or unit: take them as given.

The food record to check:
{record}

Call {tool} exactly once."""

# What the correct food tool is asked to write; `reason` is a JSON string.
NUTRITION_TASK = (
    "The nutrition values of the record below look implausible for that food and amount: "
    "{reason}. Write the record again. A value the user stated is right however unusual it "
    "looks, so keep it; change only the values the user did not state. Keep its name, amount, "
    "unit and date."
)


@dataclass(frozen=True)
class NutritionVerdict:
    valid: bool
    reason: str


def nutrition_verdict(raw: str) -> NutritionVerdict:
    """Read verifyFoodNutrition's arguments: a boolean and a text reason. A rejection that
    gives no reason, and arguments that do not fit the tool, are rejected."""
    value = object_value(arguments(raw), ("valid", "reason"))
    valid, reason = value.get("valid"), text_value(value.get("reason"))
    if not isinstance(valid, bool) or reason is None or not (valid or reason.strip()):
        raise ArgumentsRejected
    return NutritionVerdict(valid, reason)


def nutrition_question(food: Message) -> Question[NutritionVerdict]:
    prompt = VERIFY_PROMPT.format(
        sources=NUTRITION_SOURCES, record=nutrition_text(food), tool=VERIFY_FOOD_NUTRITION.name
    )
    return Question(prompt, VERIFY_FOOD_NUTRITION, nutrition_verdict)


async def check_content(foods: FoodList, evidence: Evidence, context: ToolContext) -> FoodList:
    """Return the list with the nutrition values this check rejected written again.

    With the nutrition check switched off (`RunSettings.check_nutrition`), the list comes
    back as it is and no request is made. This requests no Java operation. Provider failures
    and cancellation propagate, so nothing is saved after them.
    """
    if not context.settings.check_nutrition:
        return foods
    checked = await together(_checked(food, evidence, context) for food in foods.foods)
    corrected = sum(new is not old for new, old in zip(checked, foods.foods, strict=True))
    log.info("Food nutrition checked: foods=%d corrected=%d", len(checked), corrected)
    return FoodList(tuple(checked), foods.removed)


async def _checked(food: Message, evidence: Evidence, context: ToolContext) -> Message:
    """One food after its check: the same food when it passed, when the check could not be
    answered, or when its correction could not be; otherwise with corrected nutrition values."""
    verdict = await ask(context, nutrition_question(food), OPTIONAL_ATTEMPTS)
    if verdict is None or verdict.valid:
        return food
    task = NUTRITION_TASK.format(reason=json.dumps(verdict.reason, ensure_ascii=False))
    correction = food_correction(evidence, task, record_text(food))
    corrected = await ask(context, correction, OPTIONAL_ATTEMPTS)
    return food if corrected is None else with_nutrition(food, corrected)
