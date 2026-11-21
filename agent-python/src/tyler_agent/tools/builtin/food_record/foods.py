"""The foods of one recordFood call, held in memory while the checks run.

A food here is `{"food": {"genericInfo": ..., "macroNutrients": ...}}`, as `food_arguments`
writes it, with numbers as exact decimal text. The inner record is one entry of
`food.insert_all`'s `foods` list. Either part can be None when the chat model left it out;
Java still decides whether such a record can be saved.
"""

from collections.abc import Collection, Iterator, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.parsing import GENERIC_INFO_FIELDS, MACRO_FIELDS
from tyler_agent.tools.rendering import pretty

# What the name and count check is shown of each food, beside its number.
PORTION_FIELDS = ("foodName", "amount", "unit")


class Change(Enum):
    """What a check did to a food, or could not do."""

    REMOVED = "removed"
    ADDED = "added"
    AMOUNT_CORRECTED = "amount corrected"
    DATE_CORRECTED = "date corrected"
    NUTRITION_CORRECTED = "nutrition corrected"
    # The content check gave no usable answer, so the food went on unchecked.
    NUTRITION_NOT_CHECKED = "nutrition not checked"
    # The content check rejected the food, and its correction gave no usable answer.
    NUTRITION_NOT_CORRECTED = "nutrition not corrected"


@dataclass(frozen=True)
class Finding:
    """One thing a check did to the list, and why. `check` is the name of the check's tool,
    and `food` the name the food has in the list, which is None when the model gave none.
    For a food a check added, `reason` is the food as the user described it."""

    check: str
    food: str | None
    change: Change
    reason: str


@dataclass(frozen=True)
class FoodList:
    """The foods a recordFood call would save, in order, and what the checks have found.

    A list never changes in place: `revised` returns a new one. A check points at a food by
    its number, which is its place in `foods` counted from 1, as the model is shown it.
    """

    foods: tuple[Message, ...]
    findings: tuple[Finding, ...] = ()

    def numbered(self) -> Iterator[tuple[int, Message]]:
        return enumerate(self.foods, start=1)

    def revised(
        self,
        *,
        replaced: Mapping[int, Message] | None = None,
        removed: Collection[int] = (),
        added: Sequence[Message] = (),
        findings: Sequence[Finding] = (),
    ) -> "FoodList":
        """Apply one check's result. `replaced` gives a food's new record and `removed` the
        foods taken out, both by a food's number in this list; a food that is both is taken
        out. `added` foods follow the ones that stay, in the order given. `findings` say what
        the check did and why, and follow the findings of the checks before it."""
        replaced = replaced or {}
        kept = [
            replaced.get(number, food) for number, food in self.numbered() if number not in removed
        ]
        return FoodList((*kept, *added), (*self.findings, *findings))


def food_name(food: Message) -> str | None:
    """The name the model gave a food; None when it gave no general info or no name."""
    info = food["food"]["genericInfo"]
    return None if info is None else info["foodName"]


def has_date(food: Message) -> bool:
    """Whether the model gave a food a date at all. A missing or blank date is not a date."""
    date = _info(food)["date"]
    return date is not None and bool(date.strip())


def record_text(food: Message) -> str:
    """One food's record as a check or a correction is shown it."""
    return pretty(food["food"])


def listed(foods: FoodList, fields: Sequence[str]) -> list[Message]:
    """Each food's number with these fields of its general info, as a check lists the foods
    for the model: only what that check judges."""
    return [
        {"number": number} | {field: _info(food)[field] for field in fields}
        for number, food in foods.numbered()
    ]


def portions_text(foods: FoodList) -> str:
    """Each food's number, name, amount and unit, as the name and count check is shown them."""
    return pretty(listed(foods, PORTION_FIELDS))


def with_date(food: Message, date: str) -> Message:
    """`food` with this date; everything else stays."""
    return {
        "food": {
            "genericInfo": _info(food) | {"date": date},
            "macroNutrients": food["food"]["macroNutrients"],
        }
    }


def with_portion(food: Message, corrected: Message) -> Message:
    """`food` with the amount, unit and nutrition values of `corrected`; its name and date stay.

    The nutrition values belong to the amount, so a corrected amount brings its own.
    """
    amount = {field: _info(corrected)[field] for field in ("amount", "unit", "calories")}
    return {
        "food": {
            "genericInfo": _info(food) | amount,
            "macroNutrients": corrected["food"]["macroNutrients"],
        }
    }


def nutrition_text(food: Message) -> str:
    """One food's name, amount, unit and nutrition values, as the content check is shown it.
    Its date has nothing to do with its nutrition, so it is left out."""
    macros: Message | None = food["food"]["macroNutrients"]
    shown = {field: _info(food)[field] for field in ("foodName", "amount", "unit", "calories")}
    return pretty(shown | (dict.fromkeys(MACRO_FIELDS) if macros is None else macros))


def with_nutrition(food: Message, corrected: Message) -> Message:
    """`food` with the calories and other nutrition values of `corrected`; its name, amount,
    unit and date stay."""
    return {
        "food": {
            "genericInfo": _info(food) | {"calories": _info(corrected)["calories"]},
            "macroNutrients": corrected["food"]["macroNutrients"],
        }
    }


def _info(food: Message) -> Message:
    """A food's general info, with every field None when the model gave none."""
    info: Message | None = food["food"]["genericInfo"]
    return dict.fromkeys(GENERIC_INFO_FIELDS) if info is None else info
