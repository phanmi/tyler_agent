"""Command line for the harness: choose cases and models, run the chats, print the report."""

import argparse
import asyncio
import json
import os
import sys
from collections.abc import Callable, Mapping, Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import TextIO

from evals.cases import Case, InvalidCases, load_cases, select_cases
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
# The environment variable that holds each provider's API key.
KEY_VARIABLES = {
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
    "deepseek": "DEEPSEEK_API_KEY",
}


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
    command.add_argument(
        "--model",
        dest="targets",
        action="append",
        required=True,
        type=parse_target,
        metavar="PROVIDER:MODEL",
        help="a model to evaluate, such as openai:gpt-6-sol; repeat for several",
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
    return command


def api_keys(targets: Sequence[Target], environment: Mapping[str, str]) -> dict[str, str]:
    """The key of every requested provider. Any missing key stops the run before a call is
    made, and the message names the variable to set, never a key."""
    variables = {target.provider: KEY_VARIABLES[target.provider] for target in targets}
    missing = [name for name in variables.values() if not environment.get(name)]
    if missing:
        raise UsageError(f"Set {' and '.join(missing)} before running the harness.")
    return {provider: environment[name] for provider, name in variables.items()}


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


def main(
    arguments: Sequence[str] | None = None,
    environment: Mapping[str, str] = os.environ,
    opener: ProviderFactory = open_provider,
    output: TextIO = sys.stdout,
    progress: TextIO = sys.stderr,
) -> int:
    """Run the harness and report it. A run with failed chats still finishes, and the report
    counts them. A run stopped with Ctrl-C reports the chats that had finished."""
    options = parser().parse_args(arguments)
    try:
        cases = select_cases(load_cases(), options.cases, options.categories)
        keys = api_keys(options.targets, environment)
        results = results_file(options.results, datetime.now(UTC))
    except (InvalidCases, UsageError) as error:
        print(f"error: {error}", file=progress)
        return EXIT_COULD_NOT_START
    prices = load_prices()
    chats = plan(cases, options.targets, options.repeats)
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
