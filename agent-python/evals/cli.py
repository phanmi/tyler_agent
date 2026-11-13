"""Command line for the harness: choose cases and models, run the chats, print the report."""

import argparse
import asyncio
import contextlib
import getpass
import os
import sys
from collections import Counter
from collections.abc import Callable, Collection, Iterator, Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import TextIO

from evals.cases import Case, InvalidCases, load_cases, select_cases
from evals.catalog import cheap_targets, suggested_targets
from evals.files import InvalidDataFile, replace_file
from evals.keys import (
    KEY_VARIABLES,
    KEYS_DIRECTORY,
    SAVE_KEY_HINT,
    KeyProblem,
    available_keys,
    require_keys,
    save_key,
    saved_keys,
)
from evals.pricing import load_prices
from evals.report import RunInfo, RunState, Scored, failures, report, stopped_targets
from evals.runner import Chat, ChatResult, ProviderFactory, Session, Target, run_chat
from evals.scoring import score
from tyler_agent.provider import open_provider
from tyler_agent.resources import release_version

DEFAULT_REPEATS = 3
EXIT_FINISHED = 0
# A model reached MAX_FAILED_CHATS_PER_MODEL with chats still to run, and those were skipped;
# the other models still ran.
EXIT_MODEL_STOPPED = 1
# The same code argparse uses for a command it cannot parse.
EXIT_COULD_NOT_START = 2
# The conventional code for a command stopped with Ctrl-C.
EXIT_INTERRUPTED = 130
# A model with this many failed chats is stopped and the run goes on with the next model: a
# revoked key, an unknown model ID or a long rate limit would fail every chat that follows.
MAX_FAILED_CHATS_PER_MODEL = 10
RESULTS_DIRECTORY = Path(__file__).parent / "results"
# Present in a results directory while a run writes its report there.
RUN_LOCK_NAME = "run.lock"

type Ask = Callable[[str], str]


class UsageError(Exception):
    """The command cannot run as asked; the message says what to change."""


def parse_target(value: str) -> Target:
    provider, separator, model = value.partition(":")
    if not separator or not model or provider not in KEY_VARIABLES:
        raise argparse.ArgumentTypeError(
            f"expected provider:model with provider one of {', '.join(KEY_VARIABLES)}; "
            f"got {value!r}"
        )
    return Target(provider, model)


def positive(value: str) -> int:
    try:
        number = int(value)
    except ValueError:
        number = 0
    if number < 1:
        raise argparse.ArgumentTypeError(f"expected a whole number of at least 1; got {value!r}")
    return number


def parser() -> argparse.ArgumentParser:
    command = argparse.ArgumentParser(
        prog="python -m evals",
        description="Run labelled food-recording cases against real providers, with food "
        "verification off and on, and report accuracy and cost. This makes paid API calls.",
    )
    action = command.add_mutually_exclusive_group(required=True)
    action.add_argument(
        "--model",
        dest="targets",
        action="append",
        type=parse_target,
        metavar="PROVIDER:MODEL",
        help="a model to evaluate, such as openai:gpt-6-sol; repeat for several",
    )
    action.add_argument(
        "--all-models",
        action="store_true",
        help="evaluate every suggested model of every provider that has an API key",
    )
    action.add_argument(
        "--cheap-models",
        action="store_true",
        help="evaluate one low-cost suggested model of every provider that has an API key",
    )
    action.add_argument(
        "--save-key",
        choices=tuple(KEY_VARIABLES),
        metavar="PROVIDER",
        help=f"ask for this provider's API key ({', '.join(KEY_VARIABLES)}), save it on this "
        "computer, and stop; the key is typed at a hidden prompt, never as an argument",
    )
    command.add_argument(
        "--repeats",
        type=positive,
        default=DEFAULT_REPEATS,
        help=f"times each case runs in each mode (default {DEFAULT_REPEATS})",
    )
    command.add_argument(
        "--case", dest="cases", action="append", default=[], metavar="ID", help="run this case"
    )
    command.add_argument(
        "--category",
        dest="categories",
        action="append",
        default=[],
        metavar="NAME",
        help="run the cases of this category",
    )
    command.add_argument(
        "--results",
        type=Path,
        default=RESULTS_DIRECTORY,
        metavar="DIRECTORY",
        help="where the Markdown report is written",
    )
    command.add_argument(
        "--keys",
        type=Path,
        default=KEYS_DIRECTORY,
        metavar="DIRECTORY",
        help=f"where saved API keys are kept (default {KEYS_DIRECTORY})",
    )
    return command


def chosen_targets(
    options: argparse.Namespace, available: Mapping[str, str], progress: TextIO
) -> list[Target]:
    """The models to run. Named models must all have a key. With --all-models or
    --cheap-models a provider without a key is left out and named, so the rest can still run."""
    if not (options.all_models or options.cheap_models):
        targets: list[Target] = options.targets
        require_keys([target.provider for target in targets], available)
        return targets
    listed = cheap_targets() if options.cheap_models else suggested_targets()
    for provider in KEY_VARIABLES:
        if provider not in available:
            print(f"Skipping {provider}: no API key is saved or set for it.", file=progress)
    targets = [target for target in listed if target.provider in available]
    if not targets:
        raise UsageError(f"No API key is saved or set. Save one with `{SAVE_KEY_HINT}`.")
    return targets


def plan(cases: Sequence[Case], targets: Sequence[Target], repeats: int) -> list[Chat]:
    """Every chat of a run. Off and on are next to each other for each case, so a provider
    slowdown during the run affects both modes alike."""
    return [
        Chat(case, target, verify, repeat)
        for target in targets
        for case in cases
        for repeat in range(1, repeats + 1)
        for verify in (False, True)
    ]


def _cannot_write(directory: Path, error: OSError) -> UsageError:
    return UsageError(f"Cannot write the report in {directory}: {error.strerror}")


@contextlib.contextmanager
def run_lock(directory: Path) -> Iterator[None]:
    """Hold the results directory for one run. A second run that finds the lock stops before
    any provider call, so two runs never write reports in the same place. The lock is removed
    when the run ends, also on Ctrl-C; a run that is killed outright leaves it behind, and
    the message says to delete it."""
    lock = directory / RUN_LOCK_NAME
    try:
        # Windows raises FileExistsError here when the path is a file, so this step is kept
        # apart from creating the lock.
        directory.mkdir(parents=True, exist_ok=True)
    except OSError as error:
        raise _cannot_write(directory, error) from error
    try:
        lock.touch(exist_ok=False)
    except FileExistsError as error:
        raise UsageError(
            f"Another run is writing reports in {directory}: {lock} exists. Wait for that run "
            "to end, or delete the file if no run is going on."
        ) from error
    except OSError as error:
        raise _cannot_write(directory, error) from error
    try:
        yield
    finally:
        # A lock that cannot be removed only makes the next run ask for it to be deleted.
        with contextlib.suppress(OSError):
            lock.unlink()


def report_file(directory: Path, started: datetime) -> Path:
    """Create the report file before any paid call, so a place that cannot be written stops
    the run first instead of losing it at the end."""
    path = directory / f"{started:%Y%m%d-%H%M%S}.md"
    try:
        path.write_text("", "utf-8")
    except OSError as error:
        raise _cannot_write(directory, error) from error
    return path


def write_report(path: Path, text: str, progress: TextIO) -> bool:
    """Replace the report with `text`, and say whether that worked. A failed write, for
    example while another program holds the file open, is reported and does not stop the
    run: the file keeps the last report written, and the next chat writes it again."""
    try:
        replace_file(path, text)
    except OSError as error:
        print(f"Could not update {path}: {error.strerror}", file=progress)
        return False
    return True


def _name(target: Target) -> str:
    return f"{target.provider}/{target.model}"


def _progress_line(number: int, total: int, chat: Chat, result: ChatResult) -> str:
    return (
        f"[{number}/{total}] {_name(chat.target)} {chat.case.id} verification={chat.mode} "
        f"repeat={chat.repeat}: {result.error or 'finished'}"
    )


async def run_all(
    chats: Sequence[Chat],
    session: Session,
    progress: TextIO,
    scored: list[Scored],
    save: Callable[[], object],
) -> None:
    """Run the chats one at a time, in order. Each finished chat is added to `scored` and
    the report is saved at once, so stopping the run keeps every chat already paid for.

    `failed` counts each model's failed chats. When a model's count reaches
    MAX_FAILED_CHATS_PER_MODEL, its remaining chats, if any, are skipped and the run goes on
    with the next model."""
    failed: Counter[Target] = Counter()
    for number, chat in enumerate(chats, start=1):
        if failed[chat.target] >= MAX_FAILED_CHATS_PER_MODEL:
            continue
        result = await run_chat(chat, session)
        scored.append(Scored(result, score(chat.case, result.saved)))
        save()
        print(_progress_line(number, len(chats), chat, result), file=progress)
        if result.error is None:
            continue
        failed[chat.target] += 1
        left = any(later.target == chat.target for later in chats[number:])
        if failed[chat.target] == MAX_FAILED_CHATS_PER_MODEL and left:
            print(
                f"Stopping {_name(chat.target)}: {MAX_FAILED_CHATS_PER_MODEL} chats failed. "
                "Its remaining chats are skipped.",
                file=progress,
            )


def run_until_done(
    chats: Sequence[Chat],
    session: Session,
    progress: TextIO,
    scored: list[Scored],
    save: Callable[[], object],
) -> RunState:
    """Run every chat, or until Ctrl-C, and say which of the two happened."""
    try:
        asyncio.run(run_all(chats, session, progress, scored, save))
    except KeyboardInterrupt:
        print(f"Interrupted after {len(scored)} of {len(chats)} chats.", file=progress)
        return RunState.INTERRUPTED
    return RunState.FINISHED


def failure_summary(failed: Mapping[Target, int], stopped: Collection[Target]) -> str:
    """One line with the number of failed chats, and each failing model's share."""
    if not failed:
        return "Failed chats: 0."
    shares = "; ".join(
        f"{_name(target)} {count}" + (", stopped" if target in stopped else "")
        for target, count in failed.items()
    )
    return f"Failed chats: {sum(failed.values())} ({shares})."


def exit_code(state: RunState, stopped: Collection[Target]) -> int:
    if state is RunState.INTERRUPTED:
        return EXIT_INTERRUPTED
    if stopped:
        return EXIT_MODEL_STOPPED
    return EXIT_FINISHED


def show_report(text: str, path: Path, saved: bool, output: TextIO) -> None:
    print(text, file=output)
    if saved:
        print(f"Report: {path}", file=output)
    else:
        print(
            f"{path} was not updated at the end, so the text above is the full report.",
            file=output,
        )


def save_key_command(options: argparse.Namespace, ask: Ask, output: TextIO) -> int:
    """Ask for one provider's key without showing it, and save it."""
    provider: str = options.save_key
    key = ask(f"API key for {provider} (what you type is hidden): ").strip()
    if not key:
        raise KeyProblem("No key was entered, so nothing was saved.")
    path = save_key(options.keys, provider, key)
    print(f"Saved the {provider} key in {path}, as plain text.", file=output)
    return EXIT_FINISHED


def run_command(
    options: argparse.Namespace,
    environment: Mapping[str, str],
    opener: ProviderFactory,
    output: TextIO,
    progress: TextIO,
) -> int:
    """Run the chosen chats and report them. Every input is checked before the results
    directory is locked, so a run that cannot start leaves nothing behind. Failed chats are
    counted in the report and the summary line; a model that reaches the failure limit with
    chats still to run is stopped. A run stopped with Ctrl-C reports the chats that had
    finished."""
    cases = select_cases(load_cases(), options.cases, options.categories)
    keys = available_keys(environment, saved_keys(options.keys))
    targets = chosen_targets(options, keys, progress)
    prices = load_prices()
    # Read once: the version file is in the working tree, which can change during a long run.
    version = release_version()
    chats = plan(cases, targets, options.repeats)
    with run_lock(options.results):
        started = datetime.now(UTC)
        path = report_file(options.results, started)
        scored: list[Scored] = []

        def run_info(state: RunState) -> RunInfo:
            return RunInfo(
                started,
                version,
                len(cases),
                options.repeats,
                len(chats),
                state,
                MAX_FAILED_CHATS_PER_MODEL,
            )

        def save() -> bool:
            return write_report(path, report(scored, prices, run_info(RunState.RUNNING)), progress)

        state = run_until_done(chats, Session(keys, opener), progress, scored, save)
        final = run_info(state)
        text = report(scored, prices, final)
        saved = write_report(path, text, progress)
    show_report(text, path, saved, output)
    stopped = stopped_targets(scored, final)
    print(failure_summary(failures(scored), stopped), file=progress)
    return exit_code(state, stopped)


def main(
    arguments: Sequence[str] | None = None,
    environment: Mapping[str, str] = os.environ,
    opener: ProviderFactory = open_provider,
    output: TextIO = sys.stdout,
    progress: TextIO = sys.stderr,
    ask: Ask = getpass.getpass,
) -> int:
    """Save a key or run the harness. A command that cannot start says why and makes no
    provider call."""
    options = parser().parse_args(arguments)
    try:
        if options.save_key:
            return save_key_command(options, ask, output)
        return run_command(options, environment, opener, output, progress)
    except (InvalidCases, InvalidDataFile, UsageError, KeyProblem) as error:
        print(f"error: {error}", file=progress)
        return EXIT_COULD_NOT_START
