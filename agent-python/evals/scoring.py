"""Compare the records a chat saved with the records its case expects. No model is involved.

Each expected record is paired with an unpaired saved record whose name contains one of its
accepted names as whole words; when several qualify, the one with the fewest wrong fields
wins. A pair is correct when every checked field matches; an expected record with no pair
was missed; a saved record with no pair should not have been saved.
"""

import re
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from evals.cases import Case, Expected, Tolerance
from tyler_agent.protocol.protocol import Message

# Calories may be 20% off, the margin US labelling rules allow between a label and a lab
# analysis (21 CFR 101.9(g)(5)), so an estimate as close as a compliant label passes. The
# 15 kcal floor keeps a small item, such as a spoon of oil, from failing on a few kcal.
CALORIE_TOLERANCE = Tolerance(Decimal("0.20"), Decimal("15"))
# The same 20% for protein, carbs, fat and fiber. The 2 g floor matters when the reference is
# close to zero, such as the fat in rice, where 20% is less than a rounding step.
MACRO_TOLERANCE = Tolerance(Decimal("0.20"), Decimal("2"))

MACROS = ("protein", "carbs", "fat", "fiber")
NUTRIENTS = ("calories", *MACROS)

# Spellings a model commonly uses for one unit. A unit that is not listed is compared by its
# lower-case text without a plural "s", so "items" and "item" are the same unit.
UNIT_SPELLINGS: dict[str, tuple[str, ...]] = {
    "g": ("g", "gm", "gram", "grams"),
    "kg": ("kg", "kilogram", "kilograms"),
    "ml": ("ml", "milliliter", "milliliters", "millilitre", "millilitres"),
    "l": ("l", "liter", "liters", "litre", "litres"),
    "oz": ("oz", "ounce", "ounces"),
    "lb": ("lb", "lbs", "pound", "pounds"),
    "cup": ("cup", "cups"),
    "tbsp": ("tbsp", "tbs", "tablespoon", "tablespoons"),
    "tsp": ("tsp", "teaspoon", "teaspoons"),
    "slice": ("slice", "slices"),
    "piece": ("piece", "pieces", "pc", "pcs"),
    "serving": ("serving", "servings"),
}
_CANONICAL_UNIT = {
    spelling: unit for unit, spellings in UNIT_SPELLINGS.items() for spelling in spellings
}
_WORD = re.compile(r"[a-z0-9]+")
PLURAL_ENDINGS = ("", "s", "es")


@dataclass(frozen=True)
class ItemScore:
    """The outcome for one expected record, one saved record, or a pair of them.

    `expected` and `saved` are positions in the case's expected records and in the chat's
    saved records. `wrong` names the checked fields of a pair that did not match.
    `calorie_error` is the saved calories' distance from the reference as a fraction of it,
    for a pair whose calories were estimated; it is None when the user stated the calories.
    """

    expected: int | None
    saved: int | None
    wrong: tuple[str, ...] = ()
    calorie_error: Decimal | None = None

    @property
    def missed(self) -> bool:
        return self.saved is None

    @property
    def wrong_save(self) -> bool:
        """A saved record that is not right: nothing expected it, or a field is wrong."""
        return self.saved is not None and (self.expected is None or bool(self.wrong))

    @property
    def correct(self) -> bool:
        return self.expected is not None and self.saved is not None and not self.wrong


def score(case: Case, saved: Sequence[Message]) -> tuple[ItemScore, ...]:
    """Score every expected and every saved record of one chat, expected records first."""
    unpaired = list(range(len(saved)))
    scores: list[ItemScore] = []
    for position, expected in enumerate(case.expected):
        named = [index for index in unpaired if _named(expected, saved[index])]
        if not named:
            scores.append(ItemScore(position, None))
            continue
        # A chat can save the same food twice, for example before and after a correction.
        match = min(named, key=lambda index: len(wrong_fields(expected, saved[index])))
        unpaired.remove(match)
        scores.append(
            ItemScore(
                position,
                match,
                wrong_fields(expected, saved[match]),
                calorie_error(expected, saved[match]),
            )
        )
    scores.extend(ItemScore(None, index) for index in unpaired)
    return tuple(scores)


def fully_correct(scores: Sequence[ItemScore]) -> bool:
    """Every expected record was saved correctly and nothing else was saved."""
    return all(item.correct for item in scores)


def name_matches(saved: str, accepted: str) -> bool:
    """True when the saved name contains the accepted name as whole words, in order. A saved
    word may be a plural, so "Boiled eggs" matches "egg" and "Eggplant" does not."""
    words, wanted = _WORD.findall(saved.lower()), _WORD.findall(accepted.lower())
    return bool(wanted) and any(
        all(
            word in {target + ending for ending in PLURAL_ENDINGS}
            for word, target in zip(words[start:], wanted, strict=False)
        )
        for start in range(len(words) - len(wanted) + 1)
    )


def normal_unit(unit: str) -> str:
    text = unit.strip().lower().rstrip(".")
    if text in _CANONICAL_UNIT:
        return _CANONICAL_UNIT[text]
    return text[:-1] if len(text) > 1 and text.endswith("s") else text


def wrong_fields(expected: Expected, record: Message) -> tuple[str, ...]:
    """The checked fields of a paired record that do not match, in a fixed order."""
    return tuple(name for name, matches in _CHECKS if not matches(expected, record))


def calorie_error(expected: Expected, record: Message) -> Decimal | None:
    reference = expected.nutrition.get("calories")
    saved = _number(record, "genericInfo", "calories")
    if reference is None or saved is None or not reference or "calories" in expected.stated:
        return None
    return abs(saved - reference) / reference


def _part(record: Message, part: str) -> Message:
    value = record.get(part)
    return value if isinstance(value, dict) else {}


def _number(record: Message, part: str, field: str) -> Decimal | None:
    """A saved number, which the tool sends as decimal text; None when absent or not one."""
    value = _part(record, part).get(field)
    if not isinstance(value, str):
        return None
    try:
        number = Decimal(value)
    except InvalidOperation:
        return None
    return number if number.is_finite() else None


def _named(expected: Expected, record: Message) -> bool:
    name = _part(record, "genericInfo").get("foodName")
    if not isinstance(name, str):
        return False
    return any(name_matches(name, accepted) for accepted in expected.names)


def _amount_matches(expected: Expected, record: Message) -> bool:
    return expected.amount is None or _number(record, "genericInfo", "amount") == expected.amount


def _unit_matches(expected: Expected, record: Message) -> bool:
    if expected.amount is None:
        return True
    unit = _part(record, "genericInfo").get("unit")
    return isinstance(unit, str) and normal_unit(unit) in {
        normal_unit(accepted) for accepted in expected.units
    }


def _date_matches(expected: Expected, record: Message) -> bool:
    return _part(record, "genericInfo").get("date") == expected.date


def _nutrient(name: str) -> Callable[[Expected, Message], bool]:
    part = "genericInfo" if name == "calories" else "macroNutrients"
    default = CALORIE_TOLERANCE if name == "calories" else MACRO_TOLERANCE

    def matches(expected: Expected, record: Message) -> bool:
        reference = expected.nutrition.get(name)
        if reference is None:
            return True
        saved = _number(record, part, name)
        if saved is None:
            return False
        if name in expected.stated:
            return saved == reference
        return (expected.tolerance or default).allows(saved, reference)

    return matches


_CHECKS: tuple[tuple[str, Callable[[Expected, Message], bool]], ...] = (
    ("amount", _amount_matches),
    ("unit", _unit_matches),
    ("date", _date_matches),
    *((name, _nutrient(name)) for name in NUTRIENTS),
)
