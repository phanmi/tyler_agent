"""Cost lookup and the aggregation behind the eval report (#97)."""

import json
import re
import string
from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

import pytest
from support.eval_fakes import KEY, TARGET, USAGE, case, chat, expected, record

from evals.files import InvalidDataFile
from evals.pricing import Price, PriceTable, cost, load_prices
from evals.report import (
    RunInfo,
    RunState,
    Scored,
    Summary,
    markdown_table,
    markdown_text,
    report,
    summarize,
)
from evals.runner import Chat, ChatResult, FoodCheck, ModelCall, Target
from evals.scoring import score
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import Usage

PRICE = Price(Decimal(2), Decimal("0.2"), Decimal("2.5"), Decimal(10))
PRICES = PriceTable("2026-11-11", {TARGET.model: PRICE})
RUN = RunInfo(datetime(2026, 11, 11, 20, 15, tzinfo=UTC), "0.2.1", 1, 2, 4, RunState.RUNNING, 10)
# The models Settings suggests that had a standard price on the checked date.
PRICED_MODELS = {
    "gpt-6-sol",
    "gpt-6.1-sol",
    "gpt-6-luna",
    "claude-sonnet-5-5",
    "claude-opus-5-5",
    "claude-haiku-5-5",
    "deepseek-flash",
}


def scored(
    verify: bool,
    saved: tuple[Message, ...] = (),
    calls: int = 2,
    checks: tuple[FoodCheck, ...] = (),
    error: str | None = None,
    seconds: float = 1.0,
    repeat: int = 1,
    usage: Usage | None = USAGE,
) -> Scored:
    subject = chat(verify, repeat)
    result = ChatResult(subject, error, saved, checks, (ModelCall(usage, 0.5),) * calls, seconds)
    return Scored(result, score(subject.case, saved))


def values(summary: Summary) -> dict[str, tuple[float | None, float | None, float | None]]:
    return {row.metric.key: (row.off, row.on, row.change) for row in summary.rows}


def test_cost_prices_each_kind_of_token_at_its_own_rate() -> None:
    # 55 ordinary, 40 cached and 5 cache-write input tokens, and 10 output tokens.
    expected_cost = (55 * Decimal(2) + 40 * Decimal("0.2") + 5 * Decimal("2.5") + 10 * 10) / 10**6

    assert cost(USAGE, PRICE) == expected_cost


def test_the_shipped_price_table_lists_the_models_with_a_standard_price() -> None:
    prices = load_prices()

    assert prices.checked == "2026-11-13"
    assert prices.models.keys() == PRICED_MODELS
    sonnet = prices.models["claude-sonnet-5-5"]
    assert sonnet == Price(Decimal("2.00"), Decimal("0.10"), Decimal("2.50"), Decimal("10.00"))
    luna = prices.models["gpt-6-luna"]
    assert luna == Price(Decimal("0.10"), Decimal("0.01"), Decimal("0.125"), Decimal("0.50"))


def test_a_model_without_a_cache_write_rate_pays_the_input_rate(tmp_path: Path) -> None:
    path = tmp_path / "prices.json"
    entry = {"input": 0.30, "cachedInput": 0.006, "output": 1.20, "source": "test"}
    path.write_text(json.dumps({"checked": "2026-01-01", "models": {"m": entry}}), "utf-8")

    assert load_prices(path).models["m"].cache_write == Decimal("0.30")


@pytest.mark.parametrize(
    "content,problem",
    [
        ('{"checked": "2026-01-01", "models": {},}', "prices.json is not valid JSON: "),
        (
            '{"checked": "2026-01-01", "models": {"m": {"input": 1, "output": 2, "source": "x"}}}',
            "prices.json: $.models.m: 'cachedInput' is a required property",
        ),
        (
            '{"checked": "2026-01-01", "models": '
            '{"m": {"input": -1, "cachedInput": 0, "output": 2, "source": "x"}}}',
            "prices.json: $.models.m.input: ",
        ),
    ],
)
def test_a_price_table_that_cannot_be_used_names_the_file_and_the_problem(
    tmp_path: Path, content: str, problem: str
) -> None:
    path = tmp_path / "prices.json"
    path.write_text(content, "utf-8")

    with pytest.raises(InvalidDataFile) as caught:
        load_prices(path)

    assert str(caught.value).startswith(problem)


def test_each_metric_is_reported_off_on_and_as_the_difference() -> None:
    wrong = record(calories="900")
    chats = [
        # Off: one correct chat and one wrong save, over two repeats.
        scored(False, (record(),), calls=2, checks=(FoodCheck(0, False),), seconds=2.0),
        scored(False, (wrong,), calls=2, checks=(FoodCheck(0, False),), seconds=4.0, repeat=2),
        # On: one item accepted after a rejection, one refused.
        scored(True, (record(),), calls=5, checks=(FoodCheck(3, False),), seconds=6.0),
        scored(True, (), calls=5, checks=(FoodCheck(3, True),), seconds=10.0, repeat=2),
    ]

    (summary,) = summarize(chats, PRICES)
    result = values(summary)

    assert summary.target == TARGET and summary.priced
    assert result["chats"] == (2, 2, 0)
    assert result["errors"] == (0, 0, 0)
    assert result["compared"] == (2, 2, 0)
    assert result["fullyCorrectPercent"] == (50, 50, 0)
    assert result["wrongSaves"] == (1, 0, -1)
    assert result["missedItems"] == (0, 1, 1)
    assert result["savedAfterRejection"] == (0, 1, 1)
    assert result["refused"] == (0, 1, 1)
    assert result["modelCallsPerChat"] == (2, 5, 3)
    assert result["inputTokensPerChat"] == (200, 500, 300)
    assert result["cachedInputTokensPerChat"] == (80, 200, 120)
    assert result["cacheWriteTokensPerChat"] == (10, 25, 15)
    assert result["outputTokensPerChat"] == (20, 50, 30)
    assert result["latencyMedianSeconds"] == (3, 8, 5)
    assert result["latencyP95Seconds"] == (4, 10, 6)
    off_cost, on_cost, change = result["costPerChat"]
    assert off_cost == pytest.approx(float(2 * cost(USAGE, PRICE)))
    assert on_cost == pytest.approx(float(5 * cost(USAGE, PRICE)))
    assert change == pytest.approx(float(3 * cost(USAGE, PRICE)))


def test_median_calorie_error_uses_estimated_calories_of_matched_records() -> None:
    off = [
        scored(False, (record(calories="330"),)),
        scored(False, (record(calories="270"),), repeat=2),
        scored(False, (record(calories="360"),), repeat=3),
        scored(False, (), repeat=4),
    ]
    on = [scored(True, (), repeat=repeat) for repeat in (1, 2, 3, 4)]

    (summary,) = summarize(off + on, PRICES)

    median_off, median_on, change = values(summary)["medianCalorieErrorPercent"]
    assert median_off == pytest.approx(10.0) and median_on is None and change is None


def test_a_failed_chat_counts_as_an_error_and_in_nothing_else() -> None:
    chats = [
        scored(False, (record(),), calls=2),
        # It saved a wrong record and made calls before failing; none of that is measured.
        scored(
            False,
            (record(calories="900"),),
            calls=9,
            checks=(FoodCheck(3, True),),
            error="provider/PROVIDER_FAILURE",
            seconds=99.0,
            repeat=2,
        ),
        scored(True, (record(),), calls=3),
        scored(True, (record(),), calls=3, repeat=2),
    ]

    result = values(summarize(chats, PRICES)[0])

    assert result["chats"] == (2, 2, 0) and result["errors"] == (1, 0, -1)
    assert result["fullyCorrectPercent"][0] == 100
    assert result["wrongSaves"][0] == 0 and result["refused"][0] == 0
    assert result["modelCallsPerChat"][0] == 2
    assert result["latencyP95Seconds"][0] == 1.0


def test_both_modes_are_measured_over_the_chats_that_finished_in_both() -> None:
    wrong = record(calories="900")
    chats = [
        scored(False, (wrong,)),
        scored(False, (wrong,), repeat=2),
        scored(False, (wrong,), repeat=3),
        scored(True, (wrong,), calls=3),
        # Verification makes more calls, so it fails more often on a rate limit.
        scored(True, (), calls=1, error="provider/PROVIDER_FAILURE", repeat=2),
        scored(True, (), calls=1, error="timeout", repeat=3),
    ]

    result = values(summarize(chats, PRICES)[0])

    assert result["chats"] == (3, 3, 0) and result["errors"] == (0, 2, 2)
    assert result["compared"] == (1, 1, 0)
    # Counting all three off chats would show two wrong saves "removed" by failures alone.
    assert result["wrongSaves"] == (1, 1, 0)
    assert result["modelCallsPerChat"] == (2, 3, 1)


def test_a_model_missing_from_the_price_table_reports_tokens_and_no_cost() -> None:
    chats = [scored(False, (record(),)), scored(True, (record(),))]

    (summary,) = summarize(chats, PriceTable("2026-11-11", {}))
    result = values(summary)

    assert not summary.priced
    assert result["inputTokensPerChat"][0] == 200 and result["costPerChat"][0] is None
    text = report(chats, PriceTable("2026-11-11", {}), RUN)
    assert "This model has no price in the table, so no cost is shown." in text.splitlines()


def test_calls_without_usage_are_counted_and_add_no_tokens() -> None:
    chats = [scored(False, (record(),), usage=None), scored(True, (record(),))]

    result = values(summarize(chats, PRICES)[0])

    assert result["callsWithoutUsage"] == (2, 0, -2)
    assert result["inputTokensPerChat"][0] == 0 and result["costPerChat"][0] == 0


def test_each_target_gets_its_own_summary_in_run_order() -> None:
    other = Target("deepseek", "deepseek-flash")
    first = scored(False, (record(),))
    elsewhere = Chat(case(expected()), other, False, 1)
    second = Scored(ChatResult(elsewhere, None, (), (), (), 1.0), ())

    summaries = summarize([first, second], PRICES)

    assert [summary.target for summary in summaries] == [TARGET, other]
    assert [values(summary)["chats"][0] for summary in summaries] == [1, 1]


def cells(line: str) -> list[str]:
    """The cells of one Markdown table row; an escaped pipe stays inside its cell."""
    return [cell.strip() for cell in re.split(r"(?<!\\)\|", line.strip())[1:-1]]


def table_after(text: str, heading: str) -> list[list[str]]:
    """The body rows of the first table whose header starts with `heading`."""
    lines = text.splitlines()
    start = next(index for index, line in enumerate(lines) if line.startswith(f"| {heading}"))
    rows = []
    for line in lines[start + 2 :]:
        if not line.startswith("|"):
            break
        rows.append(cells(line))
    return rows


def test_a_markdown_table_is_padded_aligned_and_keeps_text_in_its_cell() -> None:
    text = markdown_table(
        ["Metric", "Off"],
        [["Chats run", "2"], [markdown_text("A | B\nC"), "10.5"], ["Two\nlines", "1"]],
        numbers_from=1,
    )

    assert text.splitlines() == [
        "| Metric    |  Off |",
        "|-----------|-----:|",
        "| Chats run |    2 |",
        "| A \\| B C  | 10.5 |",
        "| Two lines |    1 |",
    ]


# Characters CommonMark lets a backslash escape, and every other visible character, as
# regular expression classes.
PUNCTUATION = "[" + re.escape(string.punctuation) + "]"
OTHER = "[^" + re.escape(string.punctuation) + r"\s]"


@pytest.mark.parametrize(
    "text",
    [
        "A\\|B",
        "A\\\\|B",
        "[a link](https://example.test/x)",
        "![an image](https://example.test/pixel.png)",
        '<img src="x" onerror="alert(1)">',
        "</td><b>bold</b> **strong** _em_ `code` ~~gone~~ $x$ &amp;",
    ],
)
def test_text_from_a_model_is_shown_as_written_and_nothing_in_it_is_markup(text: str) -> None:
    encoded = markdown_text(text)

    # Every punctuation character is escaped, the input's backslashes included, so each one
    # shows as itself: no link, image, tag or emphasis forms, and an escaped pipe always
    # follows an odd number of backslashes, so it cannot end a table cell.
    assert re.fullmatch(rf"(?:\\{PUNCTUATION}|{OTHER}| )*", encoded), encoded
    assert re.sub(rf"\\({PUNCTUATION})", r"\1", encoded) == text
    for pipe in re.finditer(r"(\\*)\|", encoded):
        assert len(pipe.group(1)) % 2 == 1, encoded


def test_the_report_is_markdown_with_the_run_and_a_table_for_each_model() -> None:
    chats = [scored(False, (record(),), calls=2), scored(True, (record(),), calls=3)]

    text = report(chats, PRICES, replace(RUN, planned=2, state=RunState.FINISHED))
    lines = text.splitlines()

    assert lines[:6] == [
        "# Food recording eval report",
        "",
        "- **Tyler version:** 0.2.1",
        "- **Run started:** 2026-11-11 20:15 UTC",
        "- **Cases:** 1, each run 2 times with food verification off and on",
        "- **Status:** All 2 chats ran.",
    ]
    assert "## openai / configured-model" in lines and "Prices checked 2026-11-11." in lines
    header = next(index for index, line in enumerate(lines) if line.startswith("| Metric"))
    assert cells(lines[header]) == ["Metric", "Off", "On", "Change"]
    assert re.fullmatch(r"\|-+\|-+:\|-+:\|-+:\|", lines[header + 1])
    metrics = {row[0]: row[1:] for row in table_after(text, "Metric")}
    assert metrics["Model calls per chat"] == ["2.00", "3.00", "+1.00"]
    assert metrics["Chats that failed"] == ["0", "0", "+0"]
    assert metrics["Median calorie error (%)"] == ["0.7", "0.7", "+0.0"]
    assert len(metrics) == len(summarize(chats, PRICES)[0].rows)
    assert "Every chat finished and was fully correct." in lines
    assert text.endswith("\n") and KEY not in text


def test_the_report_lists_each_chat_that_failed_or_was_not_fully_correct() -> None:
    chats = [
        scored(False, (record(date="2026-11-11", calories="900"),)),
        scored(True, ()),
        scored(False, (record(), record(foodName="Fried | rice\npudding")), repeat=2),
        scored(True, (record(),), error="provider/PROVIDER_FAILURE", repeat=2),
        scored(False, (record(),), repeat=3),
        scored(True, (record(unit=None),), repeat=3),
    ]

    text = report(chats, PRICES, replace(RUN, planned=6))

    assert table_after(text, "Case") == [
        [
            "chicken",
            "off",
            "1",
            # The saved date came from the model, so its punctuation is escaped.
            "chicken breast: date 2026\\-11\\-11 (expected 2026-11-12), calories 900 "
            "(expected 300)",
        ],
        ["chicken", "on", "1", "Missed chicken breast"],
        ["chicken", "off", "2", 'Saved "Fried \\| rice pudding", which nothing expected'],
        ["chicken", "on", "2", "Failed: provider/PROVIDER_FAILURE"],
        ["chicken", "on", "3", "chicken breast: unit nothing (expected g or gram or grams)"],
    ]


def test_a_failed_chat_also_lists_what_it_saved_wrong_or_missed() -> None:
    chats = [
        # It saved a wrong record, then a later model call failed.
        scored(False, (record(calories="900"),), error="provider/PROVIDER_FAILURE"),
        # It failed before saving anything.
        scored(True, (), error="timeout"),
    ]

    text = report(chats, PRICES, replace(RUN, planned=2))

    assert table_after(text, "Case") == [
        [
            "chicken",
            "off",
            "1",
            "Failed: provider/PROVIDER_FAILURE; chicken breast: calories 900 (expected 300)",
        ],
        ["chicken", "on", "1", "Failed: timeout; Missed chicken breast"],
    ]


def test_a_model_stopped_at_the_failure_limit_is_named_and_the_run_says_it_finished() -> None:
    other = Target("deepseek", "deepseek-flash")
    finished = Scored(
        ChatResult(Chat(case(expected()), other, False, 1), None, (), (), (), 1.0), ()
    )
    chats = [
        scored(False, (), error="timeout"),
        scored(True, (), error="timeout"),
        finished,
    ]
    run = replace(RUN, planned=8, state=RunState.FINISHED, failure_limit=2)

    lines = report(chats, PRICES, run).splitlines()

    assert (
        "- **Status:** Finished: 3 of 8 chats ran. A model with 2 failed chats was stopped, "
        "and its remaining chats did not run."
    ) in lines
    assert "Stopped after 2 failed chats: 2 of 4 chats ran." in lines
    assert sum(line.startswith("Stopped after") for line in lines) == 1


def test_a_model_that_reaches_the_limit_on_its_last_chat_is_not_reported_as_stopped() -> None:
    chats = [scored(False, (), error="timeout"), scored(True, (), error="timeout")]
    # One case once in each mode: the second failure is the model's last chat.
    run = replace(RUN, repeats=1, planned=2, state=RunState.FINISHED, failure_limit=2)

    lines = report(chats, PRICES, run).splitlines()

    assert "- **Status:** All 2 chats ran." in lines
    assert not any(line.startswith("Stopped after") for line in lines)


def test_a_stated_value_that_was_changed_is_reported_as_stated() -> None:
    stated = expected(nutrition={"calories": Decimal(1900)}, stated=frozenset({"calories"}))
    subject = Chat(case(stated), TARGET, True, 1)
    saved = (record(calories="105"),)
    chats = [Scored(ChatResult(subject, None, saved, (), (), 1.0), score(subject.case, saved))]

    text = report(chats, PRICES, RUN)

    assert table_after(text, "Case")[0][3] == (
        "chicken breast: calories 105 (expected 1900, as the user stated)"
    )


def test_a_difference_that_rounds_to_zero_is_shown_without_a_minus_sign() -> None:
    chats = [scored(False, (record(),), seconds=0.1 + 0.2), scored(True, (record(),), seconds=0.3)]

    metrics = {row[0]: row[1:] for row in table_after(report(chats, PRICES, RUN), "Metric")}

    assert metrics["Latency per chat, median (s)"] == ["0.3", "0.3", "+0.0"]


def test_a_single_repeat_is_described_as_once() -> None:
    text = report([], PRICES, replace(RUN, repeats=1))

    assert "- **Cases:** 1, each run once with food verification off and on" in text.splitlines()


def test_the_report_says_how_far_an_unfinished_run_got() -> None:
    one = [scored(False, (record(),))]

    running = report(one, PRICES, RUN)
    stopped = report(one, PRICES, replace(RUN, state=RunState.INTERRUPTED))

    assert "- **Status:** In progress: 1 of 4 chats ran so far." in running.splitlines()
    assert "- **Status:** Interrupted: 1 of 4 chats ran." in stopped.splitlines()


def test_a_run_with_no_finished_chats_still_reports() -> None:
    empty = report([], PRICES, RUN)
    assert summarize([], PRICES) == ()
    assert "- **Status:** In progress: 0 of 4 chats ran so far." in empty.splitlines()
    assert "## " not in empty

    failed = [scored(False, (), error="timeout"), scored(True, (), error="timeout")]
    result = values(summarize(failed, PRICES)[0])

    assert result["errors"] == (1, 1, 0) and result["compared"] == (0, 0, 0)
    assert result["fullyCorrectPercent"] == (None, None, None)
    assert result["latencyP95Seconds"] == (None, None, None)
    metrics = {row[0]: row[1:] for row in table_after(report(failed, PRICES, RUN), "Metric")}
    assert metrics["Fully correct chats (%)"] == ["-", "-", "-"]
