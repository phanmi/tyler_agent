# Food recording eval harness

[Python development guide](../README.md) · [Food verification](../../docs/AGENT_OPERATIONS.md#food-verification)

This harness measures how accurately the agent records food and what that costs. It sends labelled messages to a real provider through the real agent, tools and `recordFood` verification, then compares the saved records with the expected ones. Each case runs with food verification off and on, so the report shows what the check changes.

**A run makes paid API calls on your own provider account.** It is not part of the worker wheel or the Windows bundle, and CI never runs it. CI runs only its tests, which use a fake model.

## Set the API keys

The harness reads each provider's key from an environment variable and stops before any call if one is missing.

| Provider | Variable |
|---|---|
| `openai` | `OPENAI_API_KEY` |
| `anthropic` | `ANTHROPIC_API_KEY` |
| `deepseek` | `DEEPSEEK_API_KEY` |

```powershell
$env:OPENAI_API_KEY = "<your key>"
```

Keys are never written to the results file, the progress output, or the report.

## Run it

Run from `agent-python/`. Start with a few cases to check that the keys and models work:

```powershell
uv run --locked python -m evals --model openai:gpt-6-sol --case grams-chicken --case yesterday --repeats 1
```

Then run everything:

```powershell
uv run --locked python -m evals --model openai:gpt-6-sol --model anthropic:claude-sonnet-5-5 --model deepseek:deepseek-flash
```

| Option | Meaning |
|---|---|
| `--model PROVIDER:MODEL` | A model to evaluate. Repeat it for several. Required. |
| `--repeats N` | How many times each case runs in each mode. The default is 3, because model output varies. |
| `--case ID` | Run only this case. Repeat it for several. |
| `--category NAME` | Run only this category's cases. It combines with `--case`. |
| `--results DIRECTORY` | Where the results file goes. The default is `evals/results/`, which git ignores. |

A full run makes `cases × repeats × 2` chats for each model: 234 chats with the 39 shipped cases and the default 3 repeats. A chat is two or more model calls, plus one to three more for each food item when verification is on. Chats run one at a time, and each prints one progress line.

## Read the report

The command prints one block per model and writes everything it measured to a JSON file. Each row shows the value with verification off, with it on, and the change.

| Row | Meaning |
|---|---|
| Chats run, Chats that failed | A chat that fails, for example on a rate limit, is counted here and left out of every other row. The providers do not retry. |
| Fully correct chats (%) | Chats where every expected record was saved correctly and nothing else was saved. |
| Wrong saves | Saved records with a field outside tolerance, plus records that should not have been saved. |
| Missed items | Expected records that were not saved, including ones verification refused. |
| Median calorie error (%) | Over saved records that matched an expected one. Calories the user stated are left out. |
| Items saved after a rejection | Records verification rejected at least once and then accepted. |
| Items refused | Records verification rejected twice, so nothing was saved. |
| Model calls, tokens, cost | Averages per chat. They include the verification and regeneration calls inside `recordFood`. |
| Latency | Wall time per chat: the median and the 95th percentile. |
| Model calls that reported no usage | Calls whose response carried no token counts. Their tokens and cost are missing from the rows above. |

Wrong saves should fall with verification on. Missed items and cost will rise. Whether the check pays for itself is the comparison between those.

## How records are scored

Scoring uses no model. [scoring.py](scoring.py) pairs each expected record with the first saved record whose name contains one of its accepted names, ignoring case.

- **Amount and unit** must match exactly, after common unit spellings are treated as one (`g`, `gram`, `grams`). They are not checked when the user gave no amount.
- **Date** must match exactly.
- **Calories** may differ from the reference by 20% or 15 kcal, whichever is larger.
- **Protein, carbs, fat and fiber** may differ by 20% or 2 g, whichever is larger.
- **Values the user stated** must be saved exactly, however unusual they look.

The reasons for the default tolerances are next to the constants in `scoring.py`. A case can replace them.

## Add a case

Cases live in [cases/](cases), one file per category, and are checked against [case.schema.json](case.schema.json) when the harness starts.

```json
{
  "id": "yesterday",
  "now": "2026-11-12 19:30:00 EST",
  "history": [],
  "message": "Yesterday I ate 200 g of grilled chicken breast.",
  "expected": [
    {
      "names": ["chicken breast"],
      "amount": 200,
      "units": ["g", "gram", "grams"],
      "date": "2026-11-11",
      "nutrition": { "calories": 302, "protein": 61, "carbs": 0, "fat": 6.3, "fiber": 0 },
      "source": "USDA FoodData Central, SR Legacy, FDC ID 171534: ...; values for 200 g."
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | Unique across all files. Lower-case words joined by hyphens. |
| `now` | The date and time the chat happens at, in the text Java's `time.current` returns. Relative dates such as "yesterday" are counted from it. |
| `history` | Optional earlier turns, each with `role` (`user` or `assistant`) and `content`. |
| `expected` | The records the chat should save. Use `[]` for a message that should record nothing. |
| `names` | Accepted food names. A saved name matches when it contains one of them. |
| `amount`, `units` | The amount and the accepted unit spellings. Use `null` and `[]` when the user gave no amount. |
| `nutrition` | Reference values for the expected amount. A nutrient you leave out is not checked. |
| `stated` | Optional. The nutrients the user stated, which must be saved exactly. |
| `source` | Where the reference values come from. Required. |
| `tolerance` | Optional `relative` and `absolute` values that replace the defaults for this record. |

Take reference values from [USDA FoodData Central](https://fdc.nal.usda.gov/) and give the FDC ID, or from the brand's own published values. Do not estimate them.

## Update the prices

[prices.json](prices.json) holds each model's prices per million tokens, the pricing page they came from, and the date they were checked. When prices change, edit the values and the `checked` date. A model that is not in the table still gets its tokens reported, with no cost.

## Limits

- **Java's rules are not applied.** A stand-in answers the Java operations and keeps whatever `recordFood` sends. A record Java would reject, such as an impossible date, is scored as a wrong save.
- **Other tools are not answered.** Only the time, food and profile operations have a stand-in. A chat that reads or writes a file, or saves a workout plan, ends as a failed chat that names the operation.
- **Verification outcomes are inferred.** The harness counts the model calls inside each `recordFood` call. One call means the item was accepted straight away, and more than one means it was rejected at least once. This holds while verification checks one item per call.
- **A fallback model is priced as the requested one.** Claude can serve a declined request with another model. Food messages should not trigger it.
- **Prices are the standard tier.** Long-prompt and off-peak rates are noted in `prices.json` and not applied.
