"""The eval harness replaces its output files in one step (#97)."""

import os
from pathlib import Path

import pytest

from evals.files import replace_file


def test_a_replaced_file_holds_the_new_text_with_the_platforms_line_breaks(
    tmp_path: Path,
) -> None:
    path = tmp_path / "report.md"
    path.write_text("old", "utf-8")

    replace_file(path, "# Report\n\nLine two\n")

    assert path.read_text("utf-8") == "# Report\n\nLine two\n"
    assert path.read_bytes() == "# Report\n\nLine two\n".replace("\n", os.linesep).encode()
    assert [entry.name for entry in tmp_path.iterdir()] == ["report.md"]


@pytest.mark.parametrize("stop", [KeyboardInterrupt(), OSError(28, "No space left on device")])
def test_a_write_that_stops_part_way_leaves_the_old_file_whole(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, stop: BaseException
) -> None:
    path = tmp_path / "report.md"
    path.write_text("the chats saved so far", "utf-8")

    def stopped(descriptor: int) -> None:
        raise stop

    monkeypatch.setattr("evals.files.os.fsync", stopped)

    with pytest.raises(type(stop)):
        replace_file(path, "a newer report")

    assert path.read_text("utf-8") == "the chats saved so far"
    assert [entry.name for entry in tmp_path.iterdir()] == ["report.md"]
