"""Saved API keys for the eval harness: where they live and how a run finds them (#97)."""

import json
import stat
import sys
from pathlib import Path

import pytest

from evals.keys import (
    KEY_VARIABLES,
    KEYS_DIRECTORY,
    KEYS_FILE_NAME,
    KeyProblem,
    available_keys,
    require_keys,
    save_key,
    saved_keys,
)

SECRET = "SYNTHETIC_FIXTURE_KEY"


def test_keys_are_kept_in_the_users_documents_folder_outside_the_repository() -> None:
    assert KEYS_DIRECTORY == Path.home() / "Documents" / ".tylerTest"
    assert Path(__file__).parents[2] not in KEYS_DIRECTORY.parents


def test_nothing_saved_yet_is_no_keys(tmp_path: Path) -> None:
    assert saved_keys(tmp_path / "never-created") == {}


def test_a_saved_key_is_read_back_and_other_providers_are_kept(tmp_path: Path) -> None:
    directory = tmp_path / "new" / "place"

    path = save_key(directory, "openai", "first")
    save_key(directory, "deepseek", "second")
    save_key(directory, "openai", "replaced")

    assert path == directory / KEYS_FILE_NAME
    assert saved_keys(directory) == {"openai": "replaced", "deepseek": "second"}


@pytest.mark.skipif(sys.platform == "win32", reason="Windows has no owner-only file mode")
def test_the_key_file_is_readable_by_its_owner_only(tmp_path: Path) -> None:
    path = save_key(tmp_path, "openai", SECRET)

    assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.parametrize("content", ["{not json", "[]", '"text"', '{"openai": 5}'])
def test_a_file_that_does_not_hold_keys_is_refused_without_quoting_it(
    tmp_path: Path, content: str
) -> None:
    path = tmp_path / KEYS_FILE_NAME
    path.write_text(content, "utf-8")

    with pytest.raises(KeyProblem) as caught:
        saved_keys(tmp_path)

    assert str(caught.value) == (
        f"{path} does not hold saved keys. Delete it and save the keys again."
    )
    with pytest.raises(KeyProblem):
        save_key(tmp_path, "openai", SECRET)
    assert path.read_text("utf-8") == content


def test_keys_of_unknown_providers_in_the_file_are_ignored(tmp_path: Path) -> None:
    content = {"openai": SECRET, "mistral": "other"}
    tmp_path.joinpath(KEYS_FILE_NAME).write_text(json.dumps(content), "utf-8")

    assert saved_keys(tmp_path) == {"openai": SECRET}


def test_a_place_that_cannot_hold_the_file_is_reported_without_the_key(tmp_path: Path) -> None:
    occupied = tmp_path / "occupied"
    occupied.write_text("a file, not a directory", "utf-8")

    with pytest.raises(KeyProblem) as caught:
        save_key(occupied, "openai", SECRET)

    assert str(caught.value).startswith(f"Cannot save keys in {occupied}: ")
    assert SECRET not in str(caught.value)
    assert isinstance(caught.value.__cause__, OSError)


def test_an_environment_variable_wins_and_an_empty_one_does_not_count() -> None:
    environment = {"OPENAI_API_KEY": "from-environment", "DEEPSEEK_API_KEY": ""}
    saved = {"openai": "saved", "deepseek": "saved-deepseek"}

    assert available_keys(environment, saved) == {
        "openai": "from-environment",
        "deepseek": "saved-deepseek",
    }
    assert available_keys({}, {}) == {}


def test_every_provider_has_a_variable() -> None:
    assert KEY_VARIABLES == {
        "openai": "OPENAI_API_KEY",
        "anthropic": "ANTHROPIC_API_KEY",
        "deepseek": "DEEPSEEK_API_KEY",
    }


def test_only_the_requested_providers_without_a_key_are_named() -> None:
    available = {"anthropic": SECRET}

    require_keys(["anthropic", "anthropic"], available)
    with pytest.raises(KeyProblem) as caught:
        require_keys(["openai", "anthropic", "deepseek", "openai"], available)

    assert str(caught.value) == (
        "No API key for openai and deepseek. Save one with "
        "`python -m evals --save-key PROVIDER`, or set OPENAI_API_KEY and DEEPSEEK_API_KEY."
    )
    assert SECRET not in str(caught.value)
