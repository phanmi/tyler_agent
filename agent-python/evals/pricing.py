"""Turn token usage into cost with the price table in prices.json."""

import json
from collections.abc import Mapping
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

from tyler_agent.transcript import Usage

PRICES_PATH = Path(__file__).parent / "prices.json"
TOKENS_PER_PRICE_UNIT = Decimal(1_000_000)


@dataclass(frozen=True)
class Price:
    """One model's prices per million tokens. Each rate is the whole price for its kind of
    token: ordinary input, input read from the cache, input written to the cache, and output."""

    input: Decimal
    cached_input: Decimal
    cache_write: Decimal
    output: Decimal


@dataclass(frozen=True)
class PriceTable:
    """The prices by model ID, with the date they were last checked against the providers'
    pricing pages."""

    checked: str
    models: Mapping[str, Price]


def load_prices(path: Path = PRICES_PATH) -> PriceTable:
    table = json.loads(path.read_text("utf-8"), parse_float=Decimal, parse_int=Decimal)
    return PriceTable(
        table["checked"],
        {
            model: Price(
                entry["input"],
                entry["cachedInput"],
                # A provider that lists no cache-write rate bills those tokens as input.
                entry.get("cacheWrite", entry["input"]),
                entry["output"],
            )
            for model, entry in table["models"].items()
        },
    )


def cost(usage: Usage, price: Price) -> Decimal:
    """The price of one request's tokens, in the price table's currency."""
    ordinary = usage.input_tokens - usage.cached_input_tokens - usage.cache_write_tokens
    return (
        ordinary * price.input
        + usage.cached_input_tokens * price.cached_input
        + usage.cache_write_tokens * price.cache_write
        + usage.output_tokens * price.output
    ) / TOKENS_PER_PRICE_UNIT
