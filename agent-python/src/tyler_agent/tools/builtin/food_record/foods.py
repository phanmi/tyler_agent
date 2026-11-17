"""The foods of one recordFood call, held in memory while the checks run.

A food here is its food.insert input: `{"food": {"genericInfo": ..., "macroNutrients": ...}}`,
with numbers as exact decimal text. Either part can be None when the chat model left it out;
Java still decides whether such a record can be saved.
"""

from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass

from tyler_agent.protocol.protocol import Message
from tyler_agent.tools.parsing import GENERIC_INFO_FIELDS
from tyler_agent.tools.rendering import pretty

# What the name and count check is shown of each food, beside its number.
PORTION_FIELDS = ("foodName", "amount", "unit")


@dataclass(frozen=True)
class Removed:
    """A food a check took out of the list, and why, for the tool result."""

    food: Message
    reason: str


@dataclass(frozen=True)
class FoodList:
    """The foods a recordFood call would save, in order, and the foods the checks removed.

    A list never changes in place: `revised` returns a new one. A check points at a food by
    its number, which is its place in `foods` counted from 1, as the model is shown it.
    """

    foods: tuple[Message, ...]
    removed: tuple[Removed, ...] = ()

    def numbered(self) -> Iterator[tuple[int, Message]]:
        return enumerate(self.foods, start=1)

    def revised(
        self,
        *,
        replaced: Mapping[int, Message] | None = None,
        removed: Mapping[int, str] | None = None,
        added: Sequence[Message] = (),
    ) -> "FoodList":
        """Apply one check's findings, each keyed by a food's number in this list: `replaced`
        gives a food's new record and `removed` the reason a food is taken out. A food that
        is both is taken out. `added` foods follow the ones that stay, in the order given."""
        replaced, removed = replaced or {}, removed or {}
        kept = [
            replaced.get(number, food) for number, food in self.numbered() if number not in removed
        ]
        dropped = [
            Removed(food, removed[number]) for number, food in self.numbered() if number in removed
        ]
        return FoodList((*kept, *added), (*self.removed, *dropped))


def food_name(food: Message) -> str | None:
    """The name the model gave a food; None when it gave no general info or no name."""
    info = food["food"]["genericInfo"]
    return None if info is None else info["foodName"]


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


def _info(food: Message) -> Message:
    """A food's general info, with every field None when the model gave none."""
    info: Message | None = food["food"]["genericInfo"]
    return dict.fromkeys(GENERIC_INFO_FIELDS) if info is None else info
