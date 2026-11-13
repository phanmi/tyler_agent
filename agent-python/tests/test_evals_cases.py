"""The labelled eval cases load, are complete, and bad case files are refused (#97)."""

import json
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from evals.cases import PACKAGE, InvalidCases, Tolerance, load_cases, select_cases
from evals.cli import DEFAULT_REPEATS, suggested_targets

REQUIRED_CATEGORIES = {
    "amount-and-unit",
    "several-items",
    "no-amount",
    "relative-date",
    "correction",
    "household-unit",
    "branded",
    "stated-nutrition",
    "record-nothing",
}
MINIMUM_CASES = 30


def expected(**changes: Any) -> dict[str, Any]:
    value: dict[str, Any] = {
        "names": ["rice"],
        "amount": 150,
        "units": ["g"],
        "date": "2026-11-12",
        "nutrition": {"calories": 195},
        "source": "Synthetic reference for tests.",
    }
    value.update(changes)
    return {key: item for key, item in value.items() if item is not ...}


def write(directory: Path, name: str = "one.json", **case: Any) -> Path:
    value: dict[str, Any] = {
        "id": "rice",
        "now": "2026-11-12 19:30:00 EST",
        "message": "I ate rice.",
        "expected": [expected()],
    }
    value.update(case)
    directory.joinpath(name).write_text(json.dumps({"category": "test", "cases": [value]}), "utf-8")
    return directory


def test_the_shipped_cases_cover_every_required_category() -> None:
    cases = load_cases()

    assert len(cases) >= MINIMUM_CASES
    assert {case.category for case in cases} == REQUIRED_CATEGORIES
    assert len({case.id for case in cases}) == len(cases)


def test_every_shipped_reference_value_names_its_source() -> None:
    for case in load_cases():
        for item in case.expected:
            assert item.source.strip(), case.id
            # A reference is either the user's own stated value or a USDA record.
            assert item.stated == item.nutrition.keys() or "FDC ID" in item.source, case.id


def test_shipped_cases_have_the_shape_their_category_promises() -> None:
    by_category: dict[str, list[Any]] = {}
    for case in load_cases():
        by_category.setdefault(case.category, []).append(case)

    assert all(not case.expected for case in by_category["record-nothing"])
    assert all(len(case.expected) > 1 for case in by_category["several-items"])
    assert all(case.history for case in by_category["correction"])
    assert all(item.amount is None for case in by_category["no-amount"] for item in case.expected)
    assert all(item.stated for case in by_category["stated-nutrition"] for item in case.expected)
    assert any(
        item.date != case.now[:10]
        for case in by_category["relative-date"]
        for item in case.expected
    )


def test_numbers_are_read_exactly_and_optional_fields_default(tmp_path: Path) -> None:
    write(
        tmp_path,
        expected=[
            expected(
                amount=0.5,
                nutrition={"calories": 13.5, "fat": 0.1},
                stated=["fat"],
                tolerance={"relative": 0.5, "absolute": 30},
            )
        ],
        history=[{"role": "user", "content": "Hi"}],
    )

    (case,) = load_cases(tmp_path)
    (item,) = case.expected

    assert (case.id, case.category, case.history) == (
        "rice",
        "test",
        ({"role": "user", "content": "Hi"},),
    )
    assert item.amount == Decimal("0.5") and item.nutrition["fat"] == Decimal("0.1")
    assert item.stated == {"fat"}
    assert item.tolerance == Tolerance(Decimal("0.5"), Decimal(30))


@pytest.mark.parametrize(
    "case,problem",
    [
        ({"id": "Not An ID"}, "one.json: $.cases[0].id"),
        ({"now": "yesterday"}, "one.json: $.cases[0].now"),
        ({"expected": [expected(source=" ")]}, "one.json: $.cases[0].expected[0].source"),
        ({"expected": [expected(source=...)]}, "'source' is a required property"),
        ({"expected": [expected(units=[])]}, "one.json: $.cases[0].expected[0].units"),
        ({"expected": [expected(date="12/11/2026")]}, "one.json: $.cases[0].expected[0].date"),
        ({"expected": [expected(nutrition={"sugar": 1})]}, "nutrition"),
        ({"extra": 1}, "one.json: $.cases[0]"),
        (
            {"expected": [expected(stated=["protein"])]},
            "case rice marks protein as stated but gives no value",
        ),
    ],
)
def test_a_wrong_case_file_is_refused_with_the_place_of_the_problem(
    tmp_path: Path, case: dict[str, Any], problem: str
) -> None:
    with pytest.raises(InvalidCases) as caught:
        load_cases(write(tmp_path, **case))

    assert problem in str(caught.value)


def test_an_unreadable_file_a_repeated_id_and_an_empty_directory_are_refused(
    tmp_path: Path,
) -> None:
    with pytest.raises(InvalidCases, match="No case files"):
        load_cases(tmp_path)

    write(tmp_path)
    write(tmp_path, "two.json")
    with pytest.raises(InvalidCases, match="used more than once: rice"):
        load_cases(tmp_path)


def test_a_file_that_is_not_json_or_cannot_be_opened_says_why(tmp_path: Path) -> None:
    write(tmp_path)
    tmp_path.joinpath("two.json").write_text('{"category": "test",}', "utf-8")
    with pytest.raises(InvalidCases) as caught:
        load_cases(tmp_path)
    assert str(caught.value).startswith("two.json is not valid JSON: ")
    assert "line 1 column 21" in str(caught.value)
    assert isinstance(caught.value.__cause__, json.JSONDecodeError)

    tmp_path.joinpath("two.json").unlink()
    tmp_path.joinpath("folder.json").mkdir()
    with pytest.raises(InvalidCases, match="Cannot open folder.json: ") as caught:
        load_cases(tmp_path)
    assert isinstance(caught.value.__cause__, OSError)


def test_cases_are_selected_by_id_or_category_in_file_order() -> None:
    cases = load_cases()

    assert select_cases(cases, [], []) == cases
    chosen = select_cases(cases, ["a-banana"], ["record-nothing"])
    assert [case.id for case in chosen if case.category != "record-nothing"] == ["a-banana"]
    assert sum(case.category == "record-nothing" for case in chosen) == len(chosen) - 1
    assert list(chosen) == [case for case in cases if case in chosen]


def test_a_selection_that_names_nothing_real_is_refused() -> None:
    with pytest.raises(InvalidCases, match="No such case or category: no-such-case, nope"):
        select_cases(load_cases(), ["no-such-case"], ["nope", "branded"])


def test_the_readme_states_the_size_of_a_full_run() -> None:
    cases = load_cases()
    readme = PACKAGE.joinpath("README.md").read_text("utf-8")
    chats = len(cases) * DEFAULT_REPEATS * 2

    assert f"{chats} chats with the {len(cases)} shipped cases" in readme


def test_the_readme_states_the_size_of_an_all_models_run() -> None:
    readme = PACKAGE.joinpath("README.md").read_text("utf-8")
    chats = len(load_cases()) * DEFAULT_REPEATS * 2 * len(suggested_targets())

    assert f"{chats:,} chats" in readme
