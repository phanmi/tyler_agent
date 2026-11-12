"""Deterministic scoring of saved food records against a case's expected records (#97)."""

from decimal import Decimal

import pytest
from support.eval_fakes import case, expected, record

from evals.cases import Tolerance
from evals.scoring import (
    CALORIE_TOLERANCE,
    MACRO_TOLERANCE,
    ItemScore,
    fully_correct,
    normal_unit,
    score,
)


def only(scores: tuple[ItemScore, ...]) -> ItemScore:
    assert len(scores) == 1
    return scores[0]


def test_a_record_within_every_tolerance_is_correct() -> None:
    item = only(score(case(expected()), [record()]))

    assert item.correct and not item.wrong_save and not item.missed
    assert (item.expected, item.saved, item.wrong) == (0, 0, ())
    assert item.calorie_error == Decimal(2) / Decimal(300)


@pytest.mark.parametrize(
    "saved_name,accepted,matches",
    [
        ("Grilled chicken breast", ("chicken breast",), True),
        ("CHICKEN   BREAST (grilled)", ("chicken breast",), True),
        ("Chicken thigh", ("chicken breast",), False),
        ("Wholemeal toast", ("bread", "toast"), True),
        ("Rolled oats", ("oat",), True),
    ],
)
def test_a_saved_name_matches_when_it_contains_an_accepted_name(
    saved_name: str, accepted: tuple[str, ...], matches: bool
) -> None:
    scores = score(case(expected(names=accepted)), [record(foodName=saved_name)])

    assert fully_correct(scores) is matches
    if not matches:
        # The expected record was missed, and the saved one should not have been saved.
        assert [(item.missed, item.wrong_save) for item in scores] == [(True, False), (False, True)]


@pytest.mark.parametrize(
    "calories,passes",
    [("240", True), ("239.9", False), ("360", True), ("360.1", False)],
)
def test_calories_pass_within_twenty_percent(calories: str, passes: bool) -> None:
    item = only(score(case(expected()), [record(calories=calories)]))

    assert item.wrong == (() if passes else ("calories",))


@pytest.mark.parametrize(
    "reference,saved,passes",
    [
        # A small reference uses the absolute floor: 20% of 40 kcal is only 8 kcal.
        (Decimal(40), "55", True),
        (Decimal(40), "55.1", False),
    ],
)
def test_small_calorie_values_use_the_absolute_floor(
    reference: Decimal, saved: str, passes: bool
) -> None:
    subject = case(expected(nutrition={"calories": reference}))

    assert only(score(subject, [record(calories=saved)])).correct is passes


@pytest.mark.parametrize(
    "field,saved,passes",
    [
        ("protein", "48", True),
        ("protein", "47.9", False),
        # Reference fat is 6 g, so the 2 g floor decides.
        ("fat", "8", True),
        ("fat", "8.1", False),
        # Reference carbs are 0 g.
        ("carbs", "2", True),
        ("carbs", "2.1", False),
    ],
)
def test_macros_pass_within_twenty_percent_or_two_grams(
    field: str, saved: str, passes: bool
) -> None:
    item = only(score(case(expected()), [record(**{field: saved})]))

    assert item.wrong == (() if passes else (field,))


def test_the_default_tolerances_are_the_documented_values() -> None:
    assert CALORIE_TOLERANCE == Tolerance(Decimal("0.20"), Decimal("15"))
    assert MACRO_TOLERANCE == Tolerance(Decimal("0.20"), Decimal("2"))


def test_a_case_tolerance_replaces_the_defaults() -> None:
    wide = expected(tolerance=Tolerance(Decimal("0.5"), Decimal("30")))

    assert only(score(case(wide), [record(calories="450", protein="90")])).correct
    assert only(score(case(wide), [record(calories="451")])).wrong == ("calories",)


@pytest.mark.parametrize(
    "unit,passes",
    [("g", True), ("Grams", True), (" gram ", True), ("g.", True), ("oz", False), ("piece", False)],
)
def test_units_match_after_normalizing_common_spellings(unit: str, passes: bool) -> None:
    item = only(score(case(expected()), [record(unit=unit)]))

    assert item.wrong == (() if passes else ("unit",))


@pytest.mark.parametrize(
    "spelling,unit",
    [("Tablespoons", "tbsp"), ("ounces", "oz"), ("Slices", "slice"), ("fillet", "fillet")],
)
def test_unit_spellings_share_one_form_and_unknown_units_keep_their_text(
    spelling: str, unit: str
) -> None:
    assert normal_unit(spelling) == unit


def test_amount_is_compared_as_a_number_and_must_be_exact() -> None:
    assert only(score(case(expected()), [record(amount="200.0")])).correct
    assert only(score(case(expected()), [record(amount="201")])).wrong == ("amount",)


def test_amount_and_unit_are_not_checked_when_the_user_gave_none() -> None:
    unchecked = expected(amount=None, units=())

    assert only(score(case(unchecked), [record(amount="1", unit="bowl")])).correct


def test_a_wrong_date_is_a_wrong_save() -> None:
    item = only(score(case(expected()), [record(date="2026-11-11")]))

    assert item.wrong == ("date",) and item.wrong_save and not item.correct


def test_a_stated_value_must_be_saved_exactly_however_unusual() -> None:
    stated = expected(
        names=("banana",),
        amount=None,
        units=(),
        nutrition={"calories": Decimal(1900)},
        stated=frozenset({"calories"}),
    )
    banana = dict(foodName="Banana", amount="1", unit="piece", protein="1.3", carbs="27", fat="0.4")

    exact = only(score(case(stated), [record(calories="1900.0", **banana)]))
    changed = only(score(case(stated), [record(calories="105", **banana)]))
    near = only(score(case(stated), [record(calories="1899", **banana)]))

    assert exact.correct and exact.calorie_error is None
    assert changed.wrong == ("calories",) and near.wrong == ("calories",)


def test_a_nutrient_the_case_does_not_list_is_not_checked() -> None:
    calories_only = expected(nutrition={"calories": Decimal(300)})

    assert only(score(case(calories_only), [record(protein="1", fat="99")])).correct


def test_a_missing_or_malformed_saved_number_is_wrong() -> None:
    item = only(score(case(expected()), [record(calories=None, protein="lots")]))

    assert item.wrong == ("calories", "protein") and item.calorie_error is None


def test_a_case_that_should_record_nothing() -> None:
    nothing = case()

    assert score(nothing, []) == () and fully_correct(score(nothing, []))
    unexpected = only(score(nothing, [record()]))
    assert unexpected == ItemScore(None, 0) and unexpected.wrong_save
    assert not fully_correct((unexpected,))


def test_an_expected_record_that_was_not_saved_is_missed() -> None:
    scores = score(case(expected(), expected(names=("rice",))), [record()])

    assert [(item.expected, item.saved) for item in scores] == [(0, 0), (1, None)]
    assert scores[1].missed and not scores[1].wrong_save and not fully_correct(scores)


def test_a_record_saved_twice_is_one_match_and_one_wrong_save() -> None:
    scores = score(case(expected()), [record(), record()])

    assert [(item.expected, item.saved) for item in scores] == [(0, 0), (None, 1)]
    assert scores[0].correct and scores[1].wrong_save


def test_several_expected_records_pair_with_their_own_saved_records() -> None:
    rice = expected(
        names=("rice",),
        amount=Decimal(150),
        nutrition={"calories": Decimal(195)},
    )
    saved = [
        record(foodName="White rice", amount="150", calories="200"),
        record(),
    ]

    scores = score(case(expected(), rice), saved)

    assert [(item.expected, item.saved) for item in scores] == [(0, 1), (1, 0)]
    assert fully_correct(scores)
