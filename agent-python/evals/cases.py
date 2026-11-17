"""Labelled food-recording cases, read from the JSON files in cases/ and checked on load."""

from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

from evals.files import read_checked, schema_validator
from tyler_agent.protocol.protocol import Message

PACKAGE = Path(__file__).parent
CASES_DIRECTORY = PACKAGE / "cases"
SCHEMA_PATH = PACKAGE / "case.schema.json"
# The categories whose chats run after every other category's. Their chats take the longest
# and are the likeliest to fail on the run deadline, and a model that reaches the failed-chat
# limit has its remaining chats skipped. Running them last means the shorter categories have
# been measured by then.
RUN_LAST = frozenset({"many-items"})


class InvalidCases(Exception):
    """A case file is unreadable or wrong, or a selection names a case that does not exist.

    The message says which file, case or name is at fault. Cases are synthetic, so nothing
    in it is sensitive.
    """


@dataclass(frozen=True)
class Tolerance:
    """How far a saved nutrition value may be from its reference: within `relative` of the
    reference or within `absolute` of it, whichever allows more."""

    relative: Decimal
    absolute: Decimal

    def allows(self, saved: Decimal, reference: Decimal) -> bool:
        return abs(saved - reference) <= max(self.relative * abs(reference), self.absolute)


@dataclass(frozen=True)
class Portion:
    """An amount the user gave and the units a record may use with it."""

    amount: Decimal
    units: tuple[str, ...]


@dataclass(frozen=True)
class Expected:
    """One food record a case should save.

    `amount` is None when the user gave no amount, and then neither amount nor unit is
    checked. `alternatives` are other amounts the user gave for the same food, such as 2 oz
    for "one 2 oz bar"; a record may use the amount and units or any alternative. `nutrition`
    holds reference values for the expected amount; a nutrient that is not listed is not
    checked. Nutrients in `stated` were given by the user and must be saved exactly.
    `tolerance` replaces the default tolerances for this record.
    """

    names: tuple[str, ...]
    amount: Decimal | None
    units: tuple[str, ...]
    date: str
    nutrition: Mapping[str, Decimal]
    stated: frozenset[str]
    source: str
    tolerance: Tolerance | None
    alternatives: tuple[Portion, ...] = ()

    @property
    def portions(self) -> tuple[Portion, ...]:
        """Every amount and its units a record may use, the main one first; none when the user
        gave no amount."""
        if self.amount is None:
            return ()
        return (Portion(self.amount, self.units), *self.alternatives)


@dataclass(frozen=True)
class Case:
    """One chat to run: earlier turns, the user's message, the fixed time, and the records
    it should save. `now` is the text Java's time.current would return."""

    id: str
    category: str
    now: str
    history: tuple[Message, ...]
    message: str
    expected: tuple[Expected, ...]


def load_cases(directory: Path = CASES_DIRECTORY) -> tuple[Case, ...]:
    """Read every case file in name order, then move the RUN_LAST categories to the end, so
    a run always uses the same case order."""
    validator = schema_validator(SCHEMA_PATH)
    cases = [case for path in sorted(directory.glob("*.json")) for case in _file(path, validator)]
    if not cases:
        raise InvalidCases(f"No case files in {directory}")
    repeated = sorted(name for name, uses in Counter(case.id for case in cases).items() if uses > 1)
    if repeated:
        raise InvalidCases(f"Case IDs are used more than once: {', '.join(repeated)}")
    # The sort is stable, so the cases of each group keep their file order.
    return tuple(sorted(cases, key=_runs_last))


def _runs_last(case: Case) -> bool:
    return case.category in RUN_LAST


def select_cases(
    cases: Sequence[Case], ids: Sequence[str], categories: Sequence[str]
) -> tuple[Case, ...]:
    """The cases named by ID or category, in the order they loaded; all of them when neither is
    given. A name that matches nothing is an error, so a typo cannot quietly shrink a run."""
    unknown = sorted(set(ids) - {case.id for case in cases}) + sorted(
        set(categories) - {case.category for case in cases}
    )
    if unknown:
        raise InvalidCases(f"No such case or category: {', '.join(unknown)}")
    if not ids and not categories:
        return tuple(cases)
    return tuple(case for case in cases if case.id in ids or case.category in categories)


def _file(path: Path, validator: Draft202012Validator) -> list[Case]:
    # Numbers are read as exact decimals, which the scorer compares saved values with.
    document = read_checked(path, validator, InvalidCases)
    return [_case(item, document["category"], path.name) for item in document["cases"]]


def _case(item: dict[str, Any], category: str, file_name: str) -> Case:
    return Case(
        item["id"],
        category,
        item["now"],
        tuple(dict(turn) for turn in item.get("history", ())),
        item["message"],
        tuple(_expected(value, item["id"], file_name) for value in item["expected"]),
    )


def _expected(value: dict[str, Any], case_id: str, file_name: str) -> Expected:
    stated = frozenset(value.get("stated", ()))
    missing = sorted(stated - value["nutrition"].keys())
    if missing:
        raise InvalidCases(
            f"{file_name}: case {case_id} marks {', '.join(missing)} as stated but gives no "
            "value to compare with"
        )
    tolerance = value.get("tolerance")
    return Expected(
        tuple(value["names"]),
        value["amount"],
        tuple(value["units"]),
        value["date"],
        dict(value["nutrition"]),
        stated,
        value["source"],
        None if tolerance is None else Tolerance(tolerance["relative"], tolerance["absolute"]),
        tuple(
            Portion(portion["amount"], tuple(portion["units"]))
            for portion in value.get("alternatives", ())
        ),
    )
