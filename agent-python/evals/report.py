"""Aggregate scored chats into the summary table and the results file.

Every measurement covers one target (a provider and model) in one mode (verification off or
on) and uses only the chats that finished; a failed chat adds to the error count and to
nothing else. Each measurement is one entry in METRICS, so adding one changes no other code.
"""

import math
import statistics
from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass

from evals.pricing import Price, PriceTable, cost
from evals.runner import ChatResult, FoodCheck, ModelCall, Target
from evals.scoring import ItemScore, fully_correct
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import Usage

LATENCY_PERCENTILE = 95
MISSING = "-"


@dataclass(frozen=True)
class Scored:
    """One chat with the scores of its records."""

    result: ChatResult
    scores: tuple[ItemScore, ...]


@dataclass(frozen=True)
class Group:
    """One target's chats in one mode, and that model's price when the table has one."""

    chats: tuple[Scored, ...]
    price: Price | None

    @property
    def finished(self) -> tuple[Scored, ...]:
        return tuple(chat for chat in self.chats if chat.result.error is None)

    def items(self) -> list[ItemScore]:
        return [item for chat in self.finished for item in chat.scores]

    def checks(self) -> list[FoodCheck]:
        return [check for chat in self.finished for check in chat.result.checks]

    def calls(self) -> list[ModelCall]:
        return [call for chat in self.finished for call in chat.result.calls]


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


def _tokens(count: Callable[[Usage], int]) -> Callable[[Group], float | None]:
    """Mean tokens per finished chat, counting only requests that reported usage."""

    def per_chat(group: Group) -> float | None:
        return _mean(
            [
                float(sum(count(call.usage) for call in chat.result.calls if call.usage))
                for chat in group.finished
            ]
        )

    return per_chat


def _fully_correct_percent(group: Group) -> float | None:
    return _mean([100.0 if fully_correct(chat.scores) else 0.0 for chat in group.finished])


def _median_calorie_error(group: Group) -> float | None:
    errors = [
        float(item.calorie_error) * 100 for item in group.items() if item.calorie_error is not None
    ]
    return statistics.median(errors) if errors else None


def _cost_per_chat(group: Group) -> float | None:
    price = group.price
    if price is None:
        return None
    return _mean(
        [
            float(sum(cost(call.usage, price) for call in chat.result.calls if call.usage))
            for chat in group.finished
        ]
    )


def _seconds(group: Group) -> list[float]:
    return [chat.result.seconds for chat in group.finished]


METRICS: tuple[Metric, ...] = (
    Metric("chats", "Chats run", 0, lambda group: float(len(group.chats))),
    Metric("errors", "Chats that failed", 0, lambda g: float(len(g.chats) - len(g.finished))),
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
        lambda g: _mean([float(len(chat.result.calls)) for chat in g.finished]),
    ),
    Metric("inputTokensPerChat", "Input tokens per chat", 0, _tokens(lambda u: u.input_tokens)),
    Metric(
        "cachedInputTokensPerChat",
        "  of which read from cache",
        0,
        _tokens(lambda u: u.cached_input_tokens),
    ),
    Metric(
        "cacheWriteTokensPerChat",
        "  of which written to cache",
        0,
        _tokens(lambda u: u.cache_write_tokens),
    ),
    Metric("outputTokensPerChat", "Output tokens per chat", 0, _tokens(lambda u: u.output_tokens)),
    Metric("costPerChat", "Cost per chat (USD)", 5, _cost_per_chat),
    Metric(
        "latencyMedianSeconds",
        "Latency per chat, median (s)",
        1,
        lambda g: statistics.median(_seconds(g)) if g.finished else None,
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


def summarize(scored: Sequence[Scored], prices: PriceTable) -> tuple[Summary, ...]:
    """One summary per target, in the order the targets first ran."""
    targets = list(dict.fromkeys(chat.result.chat.target for chat in scored))
    summaries: list[Summary] = []
    for target in targets:
        price = prices.models.get(target.model)
        off, on = (
            Group(
                tuple(
                    chat
                    for chat in scored
                    if chat.result.chat.target == target and chat.result.chat.verify is verify
                ),
                price,
            )
            for verify in (False, True)
        )
        rows = tuple(Row(metric, metric.value(off), metric.value(on)) for metric in METRICS)
        summaries.append(Summary(target, price is not None, rows))
    return tuple(summaries)


def _shown(value: float | None, digits: int, signed: bool = False) -> str:
    if value is None:
        return MISSING
    return f"{value:+.{digits}f}" if signed else f"{value:.{digits}f}"


def table(summaries: Iterable[Summary], prices: PriceTable) -> str:
    """The summary as text: one block per target, a row per metric."""
    blocks: list[str] = []
    for summary in summaries:
        lines = [(row.metric.label, row) for row in summary.rows]
        width = max(len(label) for label, _ in lines)
        title = f"{summary.target.provider} / {summary.target.model}"
        if summary.priced:
            title += f"  (prices checked {prices.checked})"
        else:
            title += "  (no price for this model, so no cost)"
        block = [title, f"{'':<{width}}  {'off':>12}  {'on':>12}  {'change':>12}"]
        for label, row in lines:
            digits = row.metric.digits
            block.append(
                f"{label:<{width}}  {_shown(row.off, digits):>12}  {_shown(row.on, digits):>12}"
                f"  {_shown(row.change, digits, signed=True):>12}"
            )
        blocks.append("\n".join(block))
    return "\n\n".join(blocks)


def _chat_entry(chat: Scored) -> Message:
    result = chat.result
    return {
        "case": result.chat.case.id,
        "provider": result.chat.target.provider,
        "model": result.chat.target.model,
        "verification": result.chat.verify,
        "repeat": result.chat.repeat,
        "error": result.error,
        "seconds": result.seconds,
        "reply": result.reply,
        "saved": list(result.saved),
        "foodChecks": [
            {"modelCalls": check.model_calls, "refused": check.refused} for check in result.checks
        ],
        "modelCalls": [
            {
                "seconds": call.seconds,
                "usage": None
                if call.usage is None
                else {
                    "inputTokens": call.usage.input_tokens,
                    "cachedInputTokens": call.usage.cached_input_tokens,
                    "cacheWriteTokens": call.usage.cache_write_tokens,
                    "outputTokens": call.usage.output_tokens,
                },
            }
            for call in result.calls
        ],
        "scores": [
            {
                "expected": item.expected,
                "saved": item.saved,
                "wrong": list(item.wrong),
                "calorieError": None if item.calorie_error is None else float(item.calorie_error),
            }
            for item in chat.scores
        ],
    }


def document(scored: Sequence[Scored], summaries: Sequence[Summary], prices: PriceTable) -> Message:
    """Everything a run measured, as JSON values. It holds case text, saved records and
    replies, and no API key: a ChatResult never carries one."""
    return {
        "pricesChecked": prices.checked,
        "summary": [
            {
                "provider": summary.target.provider,
                "model": summary.target.model,
                "priced": summary.priced,
                "metrics": {
                    row.metric.key: {"off": row.off, "on": row.on, "change": row.change}
                    for row in summary.rows
                },
            }
            for summary in summaries
        ],
        "chats": [_chat_entry(chat) for chat in scored],
    }
