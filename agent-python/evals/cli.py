"""Command line for the harness: choose cases and models, run the chats, print the report."""

import argparse
import asyncio
import getpass
import json
import os
import sys
from collections.abc import Callable, Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import TextIO

from evals.cases import Case, InvalidCases, load_cases, select_cases
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
from evals.pricing import PriceTable, load_prices
from evals.report import Scored, document, summarize, table
from evals.runner import Chat, ProviderFactory, Session, Target, run_chat
from evals.scoring import score
from tyler_agent.provider import open_provider

DEFAULT_REPEATS = 3
EXIT_FINISHED = 0
# The same code argparse uses for a command it cannot parse.
EXIT_COULD_NOT_START = 2
# The conventional code for a command stopped with Ctrl-C.
EXIT_INTERRUPTED = 130
RESULTS_DIRECTORY = Path(__file__).parent / "results"
# The models Settings suggests for each provider, which --all-models runs.
MODELS_PATH = Path(__file__).parent / "models.json"

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
        help="where the full results file is written",
    )
    command.add_argument(
        "--keys",
        type=Path,
        default=KEYS_DIRECTORY,
        metavar="DIRECTORY",
        help=f"where saved API keys are kept (default {KEYS_DIRECTORY})",
    )
    return command


def suggested_targets() -> list[Target]:
    """Every provider's suggested models, in the order Settings lists them."""
    models: dict[str, list[str]] = json.loads(MODELS_PATH.read_text("utf-8"))
    return [Target(provider, model) for provider, names in models.items() for model in names]


def chosen_targets(
    options: argparse.Namespace, available: Mapping[str, str], progress: TextIO
) -> list[Target]:
    """The models to run. Named models must all have a key. With --all-models a provider
    without a key is left out and named, so the rest can still run."""
    if not options.all_models:
        targets: list[Target] = options.targets
        require_keys([target.provider for target in targets], available)
        return targets
    for provider in KEY_VARIABLES:
        if provider not in available:
            print(f"Skipping {provider}: no API key is saved or set for it.", file=progress)
    targets = [target for target in suggested_targets() if target.provider in available]
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


def results_file(directory: Path, started: datetime) -> Path:
    """Create the results file before any paid call, so a place that cannot be written stops
    the run first instead of losing it at the end."""
    path = directory / f"{started:%Y%m%d-%H%M%S}.json"
    try:
        directory.mkdir(parents=True, exist_ok=True)
        path.write_text("", "utf-8")
    except OSError as error:
        raise UsageError(f"Cannot write results in {directory}: {error.strerror}") from error
    return path


def save_results(path: Path, scored: Sequence[Scored], prices: PriceTable) -> None:
    text = json.dumps(
        document(scored, summarize(scored, prices), prices), indent=2, ensure_ascii=False
    )
    path.write_text(text, "utf-8")


async def run_all(
    chats: Sequence[Chat],
    session: Session,
    progress: TextIO,
    scored: list[Scored],
    save: Callable[[Sequence[Scored]], None],
) -> None:
    """Run the chats one at a time, in order. Each finished chat is added to `scored` and
    saved at once, so stopping the run keeps every chat already paid for."""
    for number, chat in enumerate(chats, start=1):
        result = await run_chat(chat, session)
        scored.append(Scored(result, score(chat.case, result.saved)))
        save(scored)
        mode = "on" if chat.verify else "off"
        print(
            f"[{number}/{len(chats)}] {chat.target.provider}/{chat.target.model} "
            f"{chat.case.id} verification={mode} repeat={chat.repeat}: "
            f"{result.error or 'finished'}",
            file=progress,
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
    """Run the chosen chats and report them. A run with failed chats still finishes, and the
    report counts them. A run stopped with Ctrl-C reports the chats that had finished."""
    cases = select_cases(load_cases(), options.cases, options.categories)
    keys = available_keys(environment, saved_keys(options.keys))
    targets = chosen_targets(options, keys, progress)
    results = results_file(options.results, datetime.now(UTC))
    prices = load_prices()
    chats = plan(cases, targets, options.repeats)
    scored: list[Scored] = []
    code = EXIT_FINISHED
    try:
        asyncio.run(
            run_all(
                chats,
                Session(keys, opener),
                progress,
                scored,
                lambda finished: save_results(results, finished, prices),
            )
        )
    except KeyboardInterrupt:
        print(f"Interrupted after {len(scored)} of {len(chats)} chats.", file=progress)
        code = EXIT_INTERRUPTED
    print(table(summarize(scored, prices), prices), file=output)
    print(file=output)
    print(f"Full results: {results}", file=output)
    return code


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
    except (InvalidCases, UsageError, KeyProblem) as error:
        print(f"error: {error}", file=progress)
        return EXIT_COULD_NOT_START
