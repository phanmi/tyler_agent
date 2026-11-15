"""Reading the models Settings offers from the backend's llm-models.properties (#103)."""

import re
from pathlib import Path

import pytest

from evals.catalog import (
    MODELS_PATH,
    PROPERTY_PREFIX,
    cheap_targets,
    read_properties,
    suggested_targets,
)
from evals.files import InvalidDataFile
from evals.keys import KEY_VARIABLES
from evals.pricing import load_prices
from evals.runner import Target

MODELS = """\
# A comment, and a blank line below.

llm.models.openai.suggested = o-1, o-2
llm.models.openai.cheap=o-2
llm.models.anthropic.suggested=a-1
llm.models.anthropic.cheap=a-1
llm.models.deepseek.default=d-1
llm.models.deepseek.suggested=d-1
llm.models.deepseek.cheap=d-1
llm.models.mistral.suggested=m-1
"""


@pytest.fixture
def models_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    path = tmp_path / "llm-models.properties"
    monkeypatch.setattr("evals.catalog.MODELS_PATH", path)
    return path


def reads_differently_in_java(line: str) -> bool:
    """Whether the backend's .properties reader would read this entry differently from
    read_properties. Java continues a value after a trailing backslash, treats any other
    backslash as an escape, and also ends a key at a colon or a space. A list with an empty
    entry gives the harness an empty model ID, which the backend refuses."""
    key, separator, value = line.partition("=")
    return (
        not separator
        or re.search(r"[\s:]", key.strip()) is not None
        or "\\" in line
        or not all(entry.strip() for entry in value.split(","))
    )


def test_the_shipped_file_names_exactly_the_providers_the_harness_runs() -> None:
    """Settings offers every provider in the file. One without a key variable here would be
    left out of an --all-models run without a "Skipping" line."""
    providers = {
        key.removeprefix(PROPERTY_PREFIX).partition(".")[0] for key in read_properties(MODELS_PATH)
    }

    assert providers == set(KEY_VARIABLES)


def test_the_shipped_file_keeps_to_the_syntax_the_harness_reads() -> None:
    """Otherwise the harness would run other models than Settings offers, and every test that
    reads the file through read_properties would still pass."""
    lines = MODELS_PATH.read_text("utf-8").splitlines()
    entries = [line for line in lines if line.strip() and not line.lstrip().startswith("#")]

    assert [line for line in entries if reads_differently_in_java(line)] == []


@pytest.mark.parametrize(
    "line",
    [
        "llm.models.openai.suggested=o-1,\\",
        "llm.models.openai.suggested=o-1,o-2,",
        "llm.models.openai.suggested: o-1",
        "llm.models.openai.suggested o-1",
        "llm.models.openai.label[o-1]=O\\u00e9 One",
    ],
)
def test_syntax_that_java_reads_differently_is_recognised(line: str) -> None:
    assert reads_differently_in_java(line)


def test_every_cheap_model_has_a_price() -> None:
    """A cheap run is for checking a change at low cost, so it must always report its cost."""
    priced = load_prices().models

    assert [target.model for target in cheap_targets() if target.model not in priced] == []


def test_lists_are_read_in_order_and_other_lines_are_skipped(models_file: Path) -> None:
    models_file.write_text(MODELS, "utf-8")

    assert suggested_targets() == [
        Target("openai", "o-1"),
        Target("openai", "o-2"),
        Target("anthropic", "a-1"),
        Target("deepseek", "d-1"),
    ]
    assert cheap_targets() == [
        Target("openai", "o-2"),
        Target("anthropic", "a-1"),
        Target("deepseek", "d-1"),
    ]


def test_a_missing_entry_is_named(models_file: Path) -> None:
    models_file.write_text(MODELS.replace("llm.models.deepseek.cheap=d-1\n", ""), "utf-8")

    with pytest.raises(
        InvalidDataFile, match=r"^llm-models\.properties: llm\.models\.deepseek\.cheap is missing$"
    ):
        cheap_targets()


@pytest.mark.parametrize("cheap", ["o-9", "o-1,o-2"])
def test_a_cheap_entry_that_is_not_one_suggested_model_is_refused(
    models_file: Path, cheap: str
) -> None:
    models_file.write_text(MODELS.replace("cheap=o-2", f"cheap={cheap}"), "utf-8")

    with pytest.raises(
        InvalidDataFile,
        match=r"^llm-models\.properties: llm\.models\.openai\.cheap must be one of "
        r"llm\.models\.openai\.suggested$",
    ):
        cheap_targets()


def test_a_missing_file_is_named(models_file: Path) -> None:
    with pytest.raises(InvalidDataFile, match=r"^Cannot open llm-models\.properties: "):
        suggested_targets()
