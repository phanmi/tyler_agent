"""Cost lookup and the aggregation behind the eval report (#97)."""

import json
from decimal import Decimal
from pathlib import Path

import pytest
from support.eval_fakes import KEY, TARGET, USAGE, case, chat, expected, record

from evals.pricing import Price, PriceTable, cost, load_prices
from evals.report import Scored, Summary, document, summarize, table
from evals.runner import Chat, ChatResult, FoodCheck, ModelCall, Target
from evals.scoring import score
from tyler_agent.protocol.protocol import Message
from tyler_agent.transcript import Usage

PRICE = Price(Decimal(2), Decimal("0.2"), Decimal("2.5"), Decimal(10))
PRICES = PriceTable("2026-11-11", {TARGET.model: PRICE})
# The models Settings suggests; the report can only price a model the table lists.
SUGGESTED_MODELS = {
    "gpt-6-sol",
    "gpt-6.1-sol",
    "gpt-5.6",
    "claude-sonnet-5-5",
    "claude-opus-5-5",
    "claude-haiku-5-5",
    "deepseek-flash",
    "deepseek-v4-pro",
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
    result = ChatResult(
        subject, error, "reply", saved, checks, (ModelCall(usage, 0.5),) * calls, seconds
    )
    return Scored(result, score(subject.case, saved))


def values(summary: Summary) -> dict[str, tuple[float | None, float | None, float | None]]:
    return {row.metric.key: (row.off, row.on, row.change) for row in summary.rows}


def test_cost_prices_each_kind_of_token_at_its_own_rate() -> None:
    # 55 ordinary, 40 cached and 5 cache-write input tokens, and 10 output tokens.
    expected_cost = (55 * Decimal(2) + 40 * Decimal("0.2") + 5 * Decimal("2.5") + 10 * 10) / 10**6

    assert cost(USAGE, PRICE) == expected_cost


def test_the_shipped_price_table_covers_the_suggested_models() -> None:
    prices = load_prices()

    assert prices.checked == "2026-11-11"
    assert prices.models.keys() == SUGGESTED_MODELS
    sonnet = prices.models["claude-sonnet-5-5"]
    assert sonnet == Price(Decimal("2.00"), Decimal("0.10"), Decimal("2.50"), Decimal("10.00"))


def test_a_model_without_a_cache_write_rate_pays_the_input_rate(tmp_path: Path) -> None:
    path = tmp_path / "prices.json"
    entry = {"input": 0.30, "cachedInput": 0.006, "output": 1.20}
    path.write_text(json.dumps({"checked": "2026-01-01", "models": {"m": entry}}), "utf-8")

    assert load_prices(path).models["m"].cache_write == Decimal("0.30")


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
    chats = [
        scored(False, (record(calories="330"),)),
        scored(False, (record(calories="270"),), repeat=2),
        scored(False, (record(calories="360"),), repeat=3),
        scored(False, (), repeat=4),
    ]

    (summary,) = summarize(chats, PRICES)

    off, on, change = values(summary)["medianCalorieErrorPercent"]
    assert off == pytest.approx(10.0) and on is None and change is None


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
    ]

    result = values(summarize(chats, PRICES)[0])

    assert result["chats"][0] == 2 and result["errors"][0] == 1
    assert result["fullyCorrectPercent"][0] == 100
    assert result["wrongSaves"][0] == 0 and result["refused"][0] == 0
    assert result["modelCallsPerChat"][0] == 2
    assert result["latencyP95Seconds"][0] == 1.0


def test_a_model_missing_from_the_price_table_reports_tokens_and_no_cost() -> None:
    (summary,) = summarize([scored(False, (record(),))], PriceTable("2026-11-11", {}))
    result = values(summary)

    assert not summary.priced
    assert result["inputTokensPerChat"][0] == 200 and result["costPerChat"][0] is None
    assert "no price for this model, so no cost" in table([summary], PRICES)


def test_calls_without_usage_are_counted_and_add_no_tokens() -> None:
    result = values(summarize([scored(False, (record(),), usage=None)], PRICES)[0])

    assert result["callsWithoutUsage"][0] == 2
    assert result["inputTokensPerChat"][0] == 0 and result["costPerChat"][0] == 0


def test_each_target_gets_its_own_summary_in_run_order() -> None:
    other = Target("deepseek", "deepseek-flash")
    first = scored(False, (record(),))
    elsewhere = Chat(case(expected()), other, False, 1)
    second = Scored(ChatResult(elsewhere, None, "", (), (), (), 1.0), ())

    summaries = summarize([first, second], PRICES)

    assert [summary.target for summary in summaries] == [TARGET, other]
    assert [values(summary)["chats"][0] for summary in summaries] == [1, 1]


def test_the_table_shows_every_metric_with_off_on_and_change() -> None:
    chats = [scored(False, (record(),), calls=2), scored(True, (record(),), calls=3)]
    text = table(summarize(chats, PRICES), PRICES)
    lines = text.splitlines()

    assert lines[0] == "openai / configured-model  (prices checked 2026-11-11)"
    assert lines[1].split() == ["off", "on", "change"]
    calls = next(line for line in lines if line.startswith("Model calls per chat"))
    assert calls.split()[-3:] == ["2.00", "3.00", "+1.00"]
    errors = next(line for line in lines if line.startswith("Chats that failed"))
    assert errors.split()[-3:] == ["0", "0", "+0"]


def test_the_results_document_is_json_and_holds_every_chat_without_a_key() -> None:
    chats = [
        scored(False, (record(),), checks=(FoodCheck(0, False),)),
        scored(True, (), error="timeout", usage=None),
    ]
    summaries = summarize(chats, PRICES)

    text = json.dumps(document(chats, summaries, PRICES))
    value = json.loads(text)

    assert KEY not in text
    assert value["pricesChecked"] == "2026-11-11"
    assert value["summary"][0]["metrics"]["chats"] == {"off": 1.0, "on": 1.0, "change": 0.0}
    first, second = value["chats"]
    assert first["case"] == "chicken" and first["verification"] is False
    assert first["saved"] == [record()]
    assert first["foodChecks"] == [{"modelCalls": 0, "refused": False}]
    assert first["modelCalls"][0]["usage"] == {
        "inputTokens": 100,
        "cachedInputTokens": 40,
        "cacheWriteTokens": 5,
        "outputTokens": 10,
    }
    assert first["scores"] == [
        {"expected": 0, "saved": 0, "wrong": [], "calorieError": pytest.approx(2 / 300)}
    ]
    assert second["error"] == "timeout" and second["modelCalls"][0]["usage"] is None
