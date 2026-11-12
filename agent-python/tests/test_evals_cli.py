"""The eval harness command: planning, key checks and one whole run with a fake model (#97)."""

import io
import json
import re
from collections.abc import AsyncIterator, Sequence
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
from support.eval_fakes import KEY, RuleProvider

from evals.cases import load_cases
from evals.cli import (
    DEFAULT_REPEATS,
    EXIT_COULD_NOT_START,
    EXIT_FINISHED,
    EXIT_INTERRUPTED,
    main,
    plan,
    suggested_targets,
)
from evals.keys import KEYS_FILE_NAME, save_key
from evals.runner import Target
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import (
    AssistantTurn,
    Provider,
    ToolDefinition,
    TranscriptEntry,
)


@asynccontextmanager
async def never_opened(value: Message) -> AsyncIterator[Provider]:
    raise AssertionError("A provider was opened although the run could not start")
    yield


@pytest.fixture(autouse=True)
def keys_directory(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Point the default key file at an empty temporary place, never the developer's own."""
    directory = tmp_path / "saved-keys"
    monkeypatch.setattr("evals.cli.KEYS_DIRECTORY", directory)
    return directory


def never_asked(prompt: str) -> str:
    raise AssertionError("The command asked for a key although none was being saved")


def run(
    arguments: list[str],
    environment: dict[str, str],
    provider: RuleProvider | None = None,
    typed: str | None = None,
) -> tuple[int, str, str]:
    output, progress = io.StringIO(), io.StringIO()
    opener = never_opened if provider is None else provider.open
    ask = never_asked if typed is None else (lambda prompt: typed)
    code = main(arguments, environment, opener, output, progress, ask)
    return code, output.getvalue(), progress.getvalue()


def test_a_missing_key_stops_the_run_before_any_provider_call() -> None:
    code, output, progress = run(
        ["--model", "openai:gpt-6-sol", "--model", "deepseek:deepseek-flash"],
        {"ANTHROPIC_API_KEY": KEY, "OPENAI_API_KEY": ""},
    )

    assert code == EXIT_COULD_NOT_START and output == ""
    assert progress == (
        "error: No API key for openai and deepseek. Save one with "
        "`python -m evals --save-key PROVIDER`, or set OPENAI_API_KEY and DEEPSEEK_API_KEY.\n"
    )


def test_a_saved_key_is_asked_for_hidden_and_then_runs_without_a_variable(
    keys_directory: Path, tmp_path: Path
) -> None:
    code, output, progress = run(["--save-key", "openai"], {}, typed=f"  {KEY}\n")

    file = keys_directory / KEYS_FILE_NAME
    assert code == EXIT_FINISHED and progress == ""
    assert output == f"Saved the openai key in {file}, as plain text.\n"
    assert json.loads(file.read_text("utf-8")) == {"openai": KEY}

    provider = RuleProvider()
    arguments = ["--model", "openai:m", "--case", "grams-chicken", "--repeats", "1"]
    code, output, progress = run([*arguments, "--results", str(tmp_path / "r")], {}, provider)

    assert code == EXIT_FINISHED and provider.keys == [KEY, KEY]
    assert KEY not in output and KEY not in progress
    assert KEY not in next((tmp_path / "r").glob("*.json")).read_text("utf-8")


def test_saving_a_second_key_keeps_the_first_and_an_empty_entry_saves_nothing(
    keys_directory: Path,
) -> None:
    run(["--save-key", "openai"], {}, typed="first")
    run(["--save-key", "deepseek"], {}, typed="second")

    code, output, progress = run(["--save-key", "anthropic"], {}, typed="   ")

    assert code == EXIT_COULD_NOT_START and output == ""
    assert progress == "error: No key was entered, so nothing was saved.\n"
    saved = json.loads((keys_directory / KEYS_FILE_NAME).read_text("utf-8"))
    assert saved == {"openai": "first", "deepseek": "second"}


def test_an_environment_variable_wins_over_a_saved_key(
    keys_directory: Path, tmp_path: Path
) -> None:
    save_key(keys_directory, "openai", "saved-key")
    provider = RuleProvider()
    arguments = ["--model", "openai:m", "--case", "grams-chicken", "--repeats", "1"]

    run([*arguments, "--results", str(tmp_path)], {"OPENAI_API_KEY": KEY}, provider)

    assert provider.keys == [KEY, KEY]


def test_the_keys_option_reads_keys_from_another_directory(tmp_path: Path) -> None:
    elsewhere = tmp_path / "other-keys"
    save_key(elsewhere, "openai", KEY)
    provider = RuleProvider()
    arguments = ["--model", "openai:m", "--case", "grams-chicken", "--repeats", "1"]

    code, _, _ = run(
        [*arguments, "--keys", str(elsewhere), "--results", str(tmp_path / "r")], {}, provider
    )

    assert code == EXIT_FINISHED and provider.keys == [KEY, KEY]


def test_all_models_runs_every_suggested_model_of_the_providers_with_a_key(
    keys_directory: Path, tmp_path: Path
) -> None:
    save_key(keys_directory, "deepseek", "deepseek-key")
    provider = RuleProvider()
    arguments = ["--all-models", "--case", "grams-chicken", "--repeats", "1"]

    code, output, progress = run(
        [*arguments, "--results", str(tmp_path)], {"OPENAI_API_KEY": KEY}, provider
    )

    assert code == EXIT_FINISHED
    assert progress.splitlines()[0] == "Skipping anthropic: no API key is saved or set for it."
    blocks = [line for line in output.splitlines() if " / " in line]
    assert [line.split("  ")[0] for line in blocks] == [
        "openai / gpt-6-sol",
        "openai / gpt-6.1-sol",
        "openai / gpt-5.6",
        "deepseek / deepseek-flash",
        "deepseek / deepseek-v4-pro",
    ]
    # One chat in each mode for each of the five models, each with its own provider's key.
    assert provider.keys == [KEY] * 6 + ["deepseek-key"] * 4


def test_all_models_without_any_key_stops_the_run() -> None:
    code, output, progress = run(["--all-models"], {})

    assert code == EXIT_COULD_NOT_START and output == ""
    assert progress.splitlines()[-1] == (
        "error: No API key is saved or set. Save one with `python -m evals --save-key PROVIDER`."
    )


def test_the_suggested_models_are_the_ones_settings_offers() -> None:
    """models.json repeats the list in LlmProvider.java, so the two must not drift apart."""
    source = Path(__file__).parents[2] / "src/main/java/org/tyler/model/llm/LlmProvider.java"
    constants = re.findall(
        r'\w+\("(\w+)",\s*"[^"]+",\s*"[^"]+",\s*List\.of\(([^)]*)\)\)', source.read_text("utf-8")
    )
    offered = [
        Target(provider, model)
        for provider, models in constants
        for model in re.findall(r'"([^"]+)"', models)
    ]

    assert len(constants) == 3 and suggested_targets() == offered


def test_an_unknown_case_stops_the_run_and_is_named() -> None:
    code, _, progress = run(
        ["--model", "openai:gpt-6-sol", "--case", "no-such-case"], {"OPENAI_API_KEY": KEY}
    )

    assert code == EXIT_COULD_NOT_START
    assert progress == "error: No such case or category: no-such-case\n"


def test_results_that_cannot_be_written_stop_the_run_before_any_provider_call(
    tmp_path: Path,
) -> None:
    occupied = tmp_path / "results"
    occupied.write_text("not a directory", "utf-8")

    code, output, progress = run(
        ["--model", "openai:gpt-6-sol", "--results", str(occupied)], {"OPENAI_API_KEY": KEY}
    )

    assert code == EXIT_COULD_NOT_START and output == ""
    assert progress.startswith(f"error: Cannot write results in {occupied}: ")


def test_an_interrupted_run_keeps_and_reports_the_chats_that_finished(tmp_path: Path) -> None:
    class Interrupted(RuleProvider):
        async def respond(
            self,
            model: str,
            transcript: Sequence[TranscriptEntry],
            tools: Sequence[ToolDefinition],
        ) -> AssistantTurn:
            if self.opened == 3:
                raise KeyboardInterrupt
            return await super().respond(model, transcript, tools)

    arguments = ["--model", "openai:m", "--case", "grams-chicken", "--results", str(tmp_path)]

    code, output, progress = run(arguments, {"OPENAI_API_KEY": KEY}, Interrupted())

    assert code == EXIT_INTERRUPTED
    assert progress.splitlines()[-1] == "Interrupted after 2 of 6 chats."
    chats = next(line for line in output.splitlines() if line.startswith("Chats run"))
    assert chats.split()[-3:] == ["1", "1", "+0"]
    (results,) = tmp_path.glob("*.json")
    saved = json.loads(results.read_text("utf-8"))
    assert [chat["verification"] for chat in saved["chats"]] == [False, True]


@pytest.mark.parametrize(
    "arguments",
    [
        [],
        ["--model", "gpt-6-sol"],
        ["--model", "mistral:large"],
        ["--model", "openai:"],
        ["--model", "openai:gpt-6-sol", "--repeats", "0"],
        ["--model", "openai:gpt-6-sol", "--repeats", "many"],
        ["--model", "openai:gpt-6-sol", "--all-models"],
        ["--all-models", "--save-key", "openai"],
        ["--save-key", "mistral"],
    ],
)
def test_a_command_that_names_no_usable_model_or_repeat_count_is_refused(
    arguments: list[str], capsys: pytest.CaptureFixture[str]
) -> None:
    with pytest.raises(SystemExit) as caught:
        run(arguments, {"OPENAI_API_KEY": KEY})

    assert caught.value.code == 2
    assert "usage: python -m evals" in capsys.readouterr().err


def test_a_full_run_makes_every_case_twice_per_repeat_for_each_model() -> None:
    cases = load_cases()
    targets = [Target("openai", "gpt-6-sol"), Target("deepseek", "deepseek-flash")]

    chats = plan(cases, targets, DEFAULT_REPEATS)

    assert DEFAULT_REPEATS == 3
    assert len(chats) == len(cases) * 2 * 3 * 2
    first = chats[:6]
    assert {chat.case.id for chat in first} == {cases[0].id}
    assert [(chat.repeat, chat.verify) for chat in first] == [
        (1, False),
        (1, True),
        (2, False),
        (2, True),
        (3, False),
        (3, True),
    ]
    assert [chat.target for chat in chats[:: len(cases) * 6]] == targets


def test_one_command_runs_the_chosen_cases_and_reports_them(tmp_path: Path) -> None:
    provider = RuleProvider()
    arguments = [
        "--model",
        "openai:configured-model",
        "--case",
        "grams-chicken",
        "--repeats",
        "2",
        "--results",
        str(tmp_path / "results"),
    ]

    code, output, progress = run(arguments, {"OPENAI_API_KEY": KEY}, provider)

    assert code == EXIT_FINISHED and provider.opened == 4
    assert progress.splitlines() == [
        f"[{number}/4] openai/configured-model grams-chicken verification={mode} "
        f"repeat={repeat}: finished"
        for number, (repeat, mode) in enumerate(
            [(1, "off"), (1, "on"), (2, "off"), (2, "on")], start=1
        )
    ]
    lines = output.splitlines()
    assert lines[0] == "openai / configured-model  (no price for this model, so no cost)"
    for label, shown in [
        ("Chats run", ["2", "2", "+0"]),
        ("Fully correct chats (%)", ["100.0", "100.0", "+0.0"]),
        ("Model calls per chat", ["2.00", "3.00", "+1.00"]),
        ("Input tokens per chat", ["200", "300", "+100"]),
        ("Cost per chat (USD)", ["-", "-", "-"]),
    ]:
        line = next(line for line in lines if line.startswith(label))
        assert line.split()[-3:] == shown, label

    (results,) = (tmp_path / "results").glob("*.json")
    assert lines[-1] == f"Full results: {results}"
    text = results.read_text("utf-8")
    assert KEY not in text and KEY not in output and KEY not in progress
    value = json.loads(text)
    assert [(chat["case"], chat["verification"], chat["repeat"]) for chat in value["chats"]] == [
        ("grams-chicken", False, 1),
        ("grams-chicken", True, 1),
        ("grams-chicken", False, 2),
        ("grams-chicken", True, 2),
    ]
    assert all(chat["scores"][0]["wrong"] == [] for chat in value["chats"])


def test_a_category_runs_all_of_its_cases(tmp_path: Path) -> None:
    provider = RuleProvider()
    arguments = ["--model", "openai:m", "--category", "record-nothing", "--repeats", "1"]

    code, output, _ = run(
        [*arguments, "--results", str(tmp_path)], {"OPENAI_API_KEY": KEY}, provider
    )

    nothing = sum(case.category == "record-nothing" for case in load_cases())
    assert code == EXIT_FINISHED and provider.opened == nothing * 2
    # The fake model records chicken for every message, which these cases must count as wrong.
    wrong = next(line for line in output.splitlines() if line.startswith("Wrong saves"))
    assert wrong.split()[-3:] == [str(nothing), str(nothing), "+0"]


def test_the_results_file_exists_before_the_first_provider_call(tmp_path: Path) -> None:
    class Checking(RuleProvider):
        @asynccontextmanager
        async def open(self, value: Message) -> AsyncIterator[Provider]:
            # A failed check here fails the chat, which the progress line would show.
            assert list(tmp_path.glob("*.json"))
            async with super().open(value) as provider:
                yield provider

    arguments = ["--model", "openai:m", "--case", "grams-chicken", "--repeats", "1"]

    code, _, progress = run(
        [*arguments, "--results", str(tmp_path)], {"OPENAI_API_KEY": KEY}, Checking()
    )

    assert code == EXIT_FINISHED
    assert [line.rsplit(": ", 1)[1] for line in progress.splitlines()] == ["finished"] * 2
