"""Provider API keys for harness runs: read from the environment or from a file on this computer.

This module is the PY-09 exception recorded in docs/PYTHON_AGENT_STANDARDS.md: the harness
may save provider keys in a plain-text file outside the repository, so a run does not need
them typed each time.
"""

import json
from collections.abc import Mapping, Sequence
from pathlib import Path

from evals.files import replace_file

# The environment variable that holds each provider's API key.
KEY_VARIABLES = {
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
    "deepseek": "DEEPSEEK_API_KEY",
}
KEYS_DIRECTORY = Path.home() / "Documents" / ".tylerTest"
KEYS_FILE_NAME = "keys.json"
# Owner read and write only, given when the file is created, where the file system has such
# permissions. Windows keeps only the read-only flag, which this mode leaves off.
KEYS_FILE_MODE = 0o600
SAVE_KEY_HINT = "uv run --locked python -m evals --save-key PROVIDER"


class KeyProblem(Exception):
    """A key is missing or the saved keys cannot be read or written. The message says what
    to do and never contains a key or the file's content."""


def saved_keys(directory: Path) -> dict[str, str]:
    """The keys saved in `directory`, by provider; none when nothing was saved yet."""
    path = directory / KEYS_FILE_NAME
    if not path.exists():
        return {}
    try:
        value = json.loads(path.read_text("utf-8"))
    except OSError as error:
        raise KeyProblem(f"Cannot open {path}: {error.strerror}") from error
    except ValueError as error:
        raise KeyProblem(_unusable(path)) from error
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value.values()):
        raise KeyProblem(_unusable(path))
    return {provider: key for provider, key in value.items() if provider in KEY_VARIABLES}


def _unusable(path: Path) -> str:
    return f"{path} does not hold saved keys. Delete it and save the keys again."


def save_key(directory: Path, provider: str, key: str) -> Path:
    """Add or replace one provider's key, keeping the others, and return the file. The new
    file is owner-only from the moment it exists and replaces the old one in one step, so a
    failed save leaves the old keys as they were."""
    keys = saved_keys(directory) | {provider: key}
    path = directory / KEYS_FILE_NAME
    try:
        directory.mkdir(parents=True, exist_ok=True)
        replace_file(path, json.dumps(keys, indent=2), KEYS_FILE_MODE)
    except OSError as error:
        raise KeyProblem(f"Cannot save keys in {directory}: {error.strerror}") from error
    return path


def available_keys(environment: Mapping[str, str], saved: Mapping[str, str]) -> dict[str, str]:
    """Each provider's key, for the providers that have one. An environment variable wins
    over a saved key, so one run can use another key without changing the file."""
    keys = {
        provider: environment.get(variable) or saved.get(provider)
        for provider, variable in KEY_VARIABLES.items()
    }
    return {provider: key for provider, key in keys.items() if key}


def require_keys(providers: Sequence[str], available: Mapping[str, str]) -> None:
    """Stop before any call when a requested provider has no key, naming how to supply it."""
    missing = [provider for provider in dict.fromkeys(providers) if provider not in available]
    if missing:
        variables = " and ".join(KEY_VARIABLES[provider] for provider in missing)
        raise KeyProblem(
            f"No API key for {' and '.join(missing)}. Save one with `{SAVE_KEY_HINT}`, "
            f"or set {variables}."
        )
