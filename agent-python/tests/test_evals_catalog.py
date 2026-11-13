"""Reading the models Settings offers from the backend's llm-models.properties (#103)."""

from pathlib import Path

import pytest

from evals.catalog import cheap_targets, suggested_targets
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


def test_the_shipped_file_lists_models_for_every_provider_the_harness_runs() -> None:
    assert {target.provider for target in suggested_targets()} == set(KEY_VARIABLES)
    assert [target.provider for target in cheap_targets()] == list(KEY_VARIABLES)


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


def test_a_missing_file_is_named(models_file: Path) -> None:
    with pytest.raises(InvalidDataFile, match=r"^Cannot open llm-models\.properties: "):
        suggested_targets()
