"""Aggregate scored chats and write them up as one Markdown report.

Every measurement covers one target (a provider and model) in one mode (verification off or
on). A chat is measured only when it finished and the chat for the same case and repeat in
the other mode finished too, so both modes are measured over the same chats and their totals
can be compared. A failed chat adds to the error count and to no measurement; the list of
problems still shows what it saved. Each measurement is one entry in METRICS, so adding one
changes no other code.
"""

import math
import statistics
import string
from collections import Counter
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import Enum

from evals.pricing import Price, PriceTable, cost
from evals.runner import ChatResult, FoodCheck, ModelCall, Target
from evals.scoring import ItemScore, expected_value, fully_correct, saved_value
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import Usage

LATENCY_PERCENTILE = 95
MISSING = "-"
# CommonMark lets any ASCII punctuation character be escaped with a backslash, and the escaped
# character then shows as itself. Escaping all of them leaves no link, image, HTML tag,
# emphasis or table delimiter in text the harness did not write. The backslash is escaped
# too, so the one before a pipe always counts, whatever the text had before it.
_MARKDOWN_ESCAPES = str.maketrans({character: f"\\{character}" for character in string.punctuation})


@dataclass(frozen=True)
class Scored:
    """One chat with the scores of its records."""

    result: ChatResult
    scores: tuple[ItemScore, ...]


@dataclass(frozen=True)
class Group:
    """One target's chats in one mode: all of them, the ones that are measured, and that
    model's price when the table has one."""

    chats: tuple[Scored, ...]
    compared: tuple[Scored, ...]
    price: Price | None

    def failed(self) -> int:
        return sum(chat.result.error is not None for chat in self.chats)

    def items(self) -> list[ItemScore]:
        return [item for chat in self.compared for item in chat.scores]

    def checks(self) -> list[FoodCheck]:
        return [check for chat in self.compared for check in chat.result.checks]

    def calls(self) -> list[ModelCall]:
        return [call for chat in self.compared for call in chat.result.calls]


@dataclass(frozen=True)
class Metric:
    """One row of the report: how to compute it for a group, and how many decimals to show."""

    key: str
    label: str
    digits: int
    value: Callable[[Group], float | None]


def _mean(values: Sequence[float]) -> float | None:
    return statistics.fmean(values) if values else None


def _percentile(values: Sequence[float], percent: int) -> float | None:
    """Nearest-rank percentile: the smallest value with at least `percent` of values at or
    below it."""
    if not values:
        return None
    return sorted(values)[math.ceil(len(values) * percent / 100) - 1]


def _usage_per_chat(group: Group, value: Callable[[Usage], int | Decimal]) -> float | None:
    """The mean over compared chats of `value` added up over a chat's requests, counting
    only requests that reported usage."""
    return _mean(
        [
            float(sum(value(call.usage) for call in chat.result.calls if call.usage))
            for chat in group.compared
        ]
    )


def _tokens(count: Callable[[Usage], int]) -> Callable[[Group], float | None]:
    """Mean tokens per finished chat, counting only requests that reported usage."""
    return lambda group: _usage_per_chat(group, count)


def _fully_correct_percent(group: Group) -> float | None:
    return _mean([100.0 if fully_correct(chat.scores) else 0.0 for chat in group.compared])


def _median_calorie_error(group: Group) -> float | None:
    errors = [
        float(item.calorie_error) * 100 for item in group.items() if item.calorie_error is not None
    ]
    return statistics.median(errors) if errors else None


def _cost_per_chat(group: Group) -> float | None:
    price = group.price
    if price is None:
        return None
    return _usage_per_chat(group, lambda usage: cost(usage, price))


def _seconds(group: Group) -> list[float]:
    return [chat.result.seconds for chat in group.compared]


METRICS: tuple[Metric, ...] = (
    Metric("chats", "Chats run", 0, lambda group: float(len(group.chats))),
    Metric("errors", "Chats that failed", 0, lambda group: float(group.failed())),
    Metric("compared", "Chats compared", 0, lambda group: float(len(group.compared))),
    Metric("fullyCorrectPercent", "Fully correct chats (%)", 1, _fully_correct_percent),
    Metric("wrongSaves", "Wrong saves", 0, lambda g: float(sum(i.wrong_save for i in g.items()))),
    Metric("missedItems", "Missed items", 0, lambda g: float(sum(i.missed for i in g.items()))),
    Metric("medianCalorieErrorPercent", "Median calorie error (%)", 1, _median_calorie_error),
    Metric(
        "savedAfterRejection",
        "Items saved after a rejection",
        0,
        lambda g: float(sum(not c.refused and c.model_calls > 1 for c in g.checks())),
    ),
    Metric("refused", "Items refused", 0, lambda g: float(sum(c.refused for c in g.checks()))),
    Metric(
        "modelCallsPerChat",
        "Model calls per chat",
        2,
        lambda g: _mean([float(len(chat.result.calls)) for chat in g.compared]),
    ),
    Metric("inputTokensPerChat", "Input tokens per chat", 0, _tokens(lambda u: u.input_tokens)),
    Metric(
        "cachedInputTokensPerChat",
        "Input tokens read from cache per chat",
        0,
        _tokens(lambda u: u.cached_input_tokens),
    ),
    Metric(
        "cacheWriteTokensPerChat",
        "Input tokens written to cache per chat",
        0,
        _tokens(lambda u: u.cache_write_tokens),
    ),
    Metric("outputTokensPerChat", "Output tokens per chat", 0, _tokens(lambda u: u.output_tokens)),
    Metric("costPerChat", "Cost per chat (USD)", 5, _cost_per_chat),
    Metric(
        "latencyMedianSeconds",
        "Latency per chat, median (s)",
        1,
        lambda g: statistics.median(_seconds(g)) if g.compared else None,
    ),
    Metric(
        "latencyP95Seconds",
        f"Latency per chat, {LATENCY_PERCENTILE}th percentile (s)",
        1,
        lambda g: _percentile(_seconds(g), LATENCY_PERCENTILE),
    ),
    Metric(
        "callsWithoutUsage",
        "Model calls that reported no usage",
        0,
        lambda g: float(sum(call.usage is None for call in g.calls())),
    ),
)


@dataclass(frozen=True)
class Row:
    """One metric for one target: verification off, on, and on minus off."""

    metric: Metric
    off: float | None
    on: float | None

    @property
    def change(self) -> float | None:
        return None if self.off is None or self.on is None else self.on - self.off


@dataclass(frozen=True)
class Summary:
    target: Target
    priced: bool
    rows: tuple[Row, ...]


def failures(scored: Sequence[Scored]) -> Counter[Target]:
    """How many chats failed for each target, in both modes together."""
    return Counter(chat.result.chat.target for chat in scored if chat.result.error is not None)


def summarize(scored: Sequence[Scored], prices: PriceTable) -> tuple[Summary, ...]:
    """One summary per target, in the order the targets first ran."""
    targets = list(dict.fromkeys(chat.result.chat.target for chat in scored))
    summaries: list[Summary] = []
    for target in targets:
        price = prices.models.get(target.model)
        modes = [
            tuple(
                chat
                for chat in scored
                if chat.result.chat.target == target and chat.result.chat.verify is verify
            )
            for verify in (False, True)
        ]
        finished_in_both = set.intersection(
            *({_pair(chat) for chat in chats if chat.result.error is None} for chats in modes)
        )
        off, on = (
            Group(chats, tuple(chat for chat in chats if _pair(chat) in finished_in_both), price)
            for chats in modes
        )
        rows = tuple(Row(metric, metric.value(off), metric.value(on)) for metric in METRICS)
        summaries.append(Summary(target, price is not None, rows))
    return tuple(summaries)


def _pair(chat: Scored) -> tuple[str, int]:
    """What the off and on chats of one comparison share: the case and the repeat."""
    return chat.result.chat.case.id, chat.result.chat.repeat


def _shown(value: float | None, digits: int, signed: bool = False) -> str:
    if value is None:
        return MISSING
    # A difference that rounds to zero must not print as "-0.0".
    value = round(value, digits) or 0.0
    return f"{value:+.{digits}f}" if signed else f"{value:.{digits}f}"


class RunState(Enum):
    """Where the run was when the report was written."""

    RUNNING = "running"
    FINISHED = "finished"
    INTERRUPTED = "interrupted"


@dataclass(frozen=True)
class RunInfo:
    """What a report says about the run itself. `planned` is the number of chats the run set
    out to make, so a report written part-way through can say how far it got. A target whose
    failed chats reach `failure_limit` while it has chats left is stopped; see
    stopped_targets."""

    started: datetime
    version: str
    cases: int
    repeats: int
    planned: int
    state: RunState
    failure_limit: int

    @property
    def chats_per_target(self) -> int:
        """Each case runs once per repeat with verification off and once with it on."""
        return self.cases * self.repeats * 2


def stopped_targets(scored: Sequence[Scored], run: RunInfo) -> frozenset[Target]:
    """The targets stopped at the failure limit: they reached it with chats still to run, and
    those chats are skipped. A target whose last chat is the one that reaches the limit ran
    in full and is not stopped."""
    ran = Counter(chat.result.chat.target for chat in scored)
    return frozenset(
        target
        for target, failed in failures(scored).items()
        if failed >= run.failure_limit and ran[target] < run.chats_per_target
    )


def _one_line(text: str) -> str:
    return " ".join(text.split())


def markdown_text(text: str) -> str:
    """Text the harness did not write, such as a saved food name, shown exactly as written:
    on one line, with nothing in it read as Markdown or HTML."""
    return _one_line(text.translate(_MARKDOWN_ESCAPES))


def markdown_table(headers: Sequence[str], rows: Sequence[Sequence[str]], numbers_from: int) -> str:
    """A Markdown table, padded so it also reads as plain text in a terminal. Columns from
    `numbers_from` on are right-aligned. Cells are Markdown and are put on one line; pass
    text the harness did not write through markdown_text first."""
    lines = [list(headers), *([_one_line(value) for value in row] for row in rows)]
    widths = [max(len(line[column]) for line in lines) for column in range(len(headers))]

    def padded(line: Sequence[str]) -> str:
        cells = [
            value.rjust(width) if column >= numbers_from else value.ljust(width)
            for column, (value, width) in enumerate(zip(line, widths, strict=True))
        ]
        return "| " + " | ".join(cells) + " |"

    rule = [
        "-" * (width + 1) + ":" if column >= numbers_from else "-" * (width + 2)
        for column, width in enumerate(widths)
    ]
    return "\n".join([padded(lines[0]), "|" + "|".join(rule) + "|", *map(padded, lines[1:])])


def _summary_table(summary: Summary) -> str:
    rows = [
        [
            row.metric.label,
            _shown(row.off, row.metric.digits),
            _shown(row.on, row.metric.digits),
            _shown(row.change, row.metric.digits, signed=True),
        ]
        for row in summary.rows
    ]
    return markdown_table(["Metric", "Off", "On", "Change"], rows, numbers_from=1)


def _saved(record: Message, field: str) -> str:
    """A saved value, which the model wrote, encoded for the report."""
    return markdown_text(saved_value(record, field))


def _problem(chat: Scored, item: ItemScore) -> str:
    """One record that was not right, with the saved and expected values that differ."""
    case, saved = chat.result.chat.case, chat.result.saved
    if item.expected is None:
        return f'Saved "{_saved(saved[item.saved or 0], "foodName")}", which nothing expected'
    expected = case.expected[item.expected]
    if item.saved is None:
        return f"Missed {expected.names[0]}"
    record = saved[item.saved]
    differences = ", ".join(
        f"{field} {_saved(record, field)} (expected {expected_value(expected, field)})"
        for field in item.wrong
    )
    return f"{expected.names[0]}: {differences}"


def _problems(chats: Sequence[Scored]) -> list[list[str]]:
    """A row for every chat that failed or was not fully correct, in run order. A failed chat
    names its failure first, then every record it got wrong before failing."""
    rows: list[list[str]] = []
    for chat in chats:
        result = chat.result
        failure = [] if result.error is None else [f"Failed: {result.error}"]
        problems = failure + [_problem(chat, item) for item in chat.scores if not item.correct]
        if not problems:
            continue
        rows.append(
            [result.chat.case.id, result.chat.mode, str(result.chat.repeat), "; ".join(problems)]
        )
    return rows


def _stopped(chats: Sequence[Scored], run: RunInfo) -> list[str]:
    """A sentence for a target stopped at the failure limit; none for any other target.
    `chats` are all one target's."""
    if not stopped_targets(chats, run):
        return []
    return [
        f"Stopped after {run.failure_limit} failed chats: {len(chats)} of "
        f"{run.chats_per_target} chats ran."
    ]


def _target_section(
    summary: Summary, chats: Sequence[Scored], prices: PriceTable, run: RunInfo
) -> str:
    price = (
        f"Prices checked {prices.checked}."
        if summary.priced
        else "This model has no price in the table, so no cost is shown."
    )
    problems = _problems(chats)
    detail = (
        markdown_table(["Case", "Verification", "Repeat", "Problem"], problems, numbers_from=4)
        if problems
        else "Every chat finished and was fully correct."
    )
    return "\n\n".join(
        [
            f"## {summary.target.provider} / {summary.target.model}",
            *_stopped(chats, run),
            price,
            _summary_table(summary),
            "### Chats that failed or were not fully correct",
            detail,
        ]
    )


def _times(count: int) -> str:
    return "once" if count == 1 else f"{count} times"


def _progress(run: RunInfo, ran: int) -> str:
    if run.state is RunState.INTERRUPTED:
        return f"Interrupted: {ran} of {run.planned} chats ran."
    if run.state is RunState.RUNNING:
        return f"In progress: {ran} of {run.planned} chats ran so far."
    if ran < run.planned:
        return (
            f"Finished: {ran} of {run.planned} chats ran. A model with {run.failure_limit} "
            "failed chats was stopped, and its remaining chats did not run."
        )
    return f"All {run.planned} chats ran."


def report(scored: Sequence[Scored], prices: PriceTable, run: RunInfo) -> str:
    """The whole run as Markdown: what was run, then one section per model. It holds case
    IDs, saved values and failure labels, and no API key: a ChatResult never carries one."""
    heading = "\n".join(
        [
            "# Food recording eval report",
            "",
            f"- **Tyler version:** {run.version}",
            f"- **Run started:** {run.started:%Y-%m-%d %H:%M} UTC",
            f"- **Cases:** {run.cases}, each run {_times(run.repeats)} with food verification "
            "off and on",
            f"- **Status:** {_progress(run, len(scored))}",
            "",
            "Each model's table compares verification off and on over the chats that finished "
            "in both modes. `agent-python/evals/README.md` explains every row.",
        ]
    )
    sections = [
        _target_section(
            summary,
            [chat for chat in scored if chat.result.chat.target == summary.target],
            prices,
            run,
        )
        for summary in summarize(scored, prices)
    ]
    return "\n\n".join([heading, *sections]) + "\n"
