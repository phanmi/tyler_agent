"""Read the harness's own JSON files and write its output files safely."""

import contextlib
import json
import os
import uuid
from decimal import Decimal
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

# The mode a new file gets before the user's umask applies: the usual default.
DEFAULT_FILE_MODE = 0o666
# Windows opens a descriptor in text mode unless told otherwise, which would turn each line
# break into two; the text layer above it already writes the platform's line breaks.
_NEW_FILE_FLAGS = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)


class InvalidDataFile(Exception):
    """A harness file cannot be read, is not JSON, or does not match its schema.

    The message names the file and the problem. These files hold cases, prices and model
    names, never a key, so quoting the problem is safe.
    """


def schema_validator(path: Path) -> Draft202012Validator:
    return Draft202012Validator(json.loads(path.read_text("utf-8")))


def read_checked(
    path: Path,
    validator: Draft202012Validator,
    problem_type: type[Exception] = InvalidDataFile,
) -> Any:
    """The JSON in `path`, checked against a schema. Numbers are read as exact decimals. A
    file that cannot be used raises `problem_type`, naming the file and what is wrong."""
    try:
        document = json.loads(path.read_text("utf-8"), parse_float=Decimal, parse_int=Decimal)
    except OSError as error:
        raise problem_type(f"Cannot open {path.name}: {error.strerror}") from error
    except ValueError as error:
        raise problem_type(f"{path.name} is not valid JSON: {error}") from error
    problem = next(iter(validator.iter_errors(document)), None)
    if problem is not None:
        raise problem_type(f"{path.name}: {problem.json_path}: {problem.message}")
    return document


def replace_file(path: Path, text: str, mode: int = DEFAULT_FILE_MODE) -> None:
    """Write `text` to a new file beside `path`, created with `mode`, then move it over `path`
    in one step. Until the move, `path` keeps its old content and mode, so a write that fails
    or is interrupted never leaves it partial. On failure the new file is removed and the
    error is raised."""
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    descriptor = os.open(temporary, _NEW_FILE_FLAGS, mode)
    try:
        with open(descriptor, "w", encoding="utf-8") as file:
            file.write(text)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temporary, path)
    except BaseException:
        # Also on Ctrl-C, so no half-written copy is left beside the file.
        with contextlib.suppress(OSError):
            temporary.unlink()
        raise
