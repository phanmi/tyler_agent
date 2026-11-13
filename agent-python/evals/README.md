# Food recording eval harness

[Python development guide](../README.md) · [Food verification](../../docs/AGENT_OPERATIONS.md#food-verification)

This harness measures how accurately the agent records food and what that costs. It sends labelled messages to a real provider through the real agent, tools and `recordFood` verification, then compares the saved records with the expected ones. Each case runs with food verification off and on, so the report shows what the check changes.

**A run makes paid API calls on your own provider account.** It is not part of the worker wheel or the Windows bundle, and CI never runs it. CI runs only its tests, which use a fake model.

## Save the API keys

Save each provider's key once. Run this from `agent-python/` for `openai`, `anthropic` and `deepseek`, whichever you have:

```powershell
uv run --locked python -m evals --save-key openai
```

The command asks for the key and hides what you type. The key is never passed as a command-line argument, so it stays out of your shell history.

Keys are saved in `Documents\.tylerTest\keys.json` under your user folder, for example `C:\Users\<you>\Documents\.tylerTest\keys.json`. That is outside the repository, so git cannot pick them up.

- **The file is plain text.** Anyone who can read your user folder can read the keys. If your `Documents` folder is synced to a cloud service, the file is synced too; use `--keys DIRECTORY` to keep it somewhere else. On Linux and macOS the file is readable by you only from the moment it is created. Saving a key writes a new file and swaps it in, so a save that fails leaves the old keys as they were.
- **To replace a key**, save it again. **To remove the keys**, delete the file.
- **An environment variable wins over a saved key**, so you can use another key for one run: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY` or `DEEPSEEK_API_KEY`.

Keys are never written to the report or the progress output.

## Run it

Run from `agent-python/`. Start with a few cases to check that the keys and models work:

```powershell
uv run --locked python -m evals --model openai:gpt-6-sol --case grams-chicken --case yesterday --repeats 1
```

Then run all cases for the models you choose:

```powershell
uv run --locked python -m evals --model openai:gpt-6-sol --model anthropic:claude-sonnet-5-5 --model deepseek:deepseek-flash
```

Or run every model Tyler suggests in Settings in one run:

```powershell
uv run --locked python -m evals --all-models
```

`--all-models` runs the models in [models.json](models.json) for every provider that has a key. A provider without a key is skipped, and the run says so. A named `--model` whose provider has no key stops the run before any call.

| Option | Meaning |
|---|---|
| `--model PROVIDER:MODEL` | A model to evaluate. Repeat it for several. |
| `--all-models` | Evaluate every suggested model of every provider that has a key. Use this or `--model`. |
| `--save-key PROVIDER` | Ask for that provider's key, save it, and stop. |
| `--repeats N` | How many times each case runs in each mode. The default is 3, because model output varies. |
| `--case ID` | Run only this case. Repeat it for several. |
| `--category NAME` | Run only this category's cases. It combines with `--case`. |
| `--results DIRECTORY` | Where the Markdown report goes. The default is `evals/results/`, which git ignores. |
| `--keys DIRECTORY` | Where saved keys are read from and written to. The default is `Documents\.tylerTest`. |

A full run makes `cases × repeats × 2` chats for each model: 234 chats with the 39 shipped cases and the default 3 repeats. `--all-models` with all three keys is eight models, so 1,872 chats. A chat is two or more model calls, plus one to three more for each food item when verification is on. Chats run one at a time, and each prints one progress line.

The report file is created before the first call and replaced after every chat, in one step, so it always holds a whole report. If it cannot be updated, for example while another program has it open, the run says so and goes on; the next chat writes it again. If you stop a run with Ctrl-C, the chats that finished are kept and reported.

### Failed chats

A chat that fails, for example on a rate limit, is counted and the run goes on. A model that reaches 10 failed chats while it still has chats to run is stopped: its remaining chats are skipped, the run goes on with the next model, and the report says which model was stopped. A model whose 10th failure is its last chat ran in full and is not stopped. A revoked key or a mistyped model ID would otherwise fail every chat of that model.

A run ends with a line that counts the failed chats of each model. It exits with:

| Code | Meaning |
|---|---|
| 0 | Every model ran to the end. Some chats may have failed; the last line counts them. |
| 1 | A model reached 10 failed chats with chats still to run, and was stopped. The other models ran. |
| 2 | The run could not start, and made no provider call. The message says why. |
| 130 | The run was stopped with Ctrl-C. |

### One run per results directory

While a run writes its report, its results directory holds a `run.lock` file. Another run with the same `--results` directory stops before any call. To run models side by side, give each run its own `--results` directory.

A run removes the lock when it ends, also after Ctrl-C. A run that is killed outright, for example by closing its window, leaves it behind; delete it when no run is going on.

## Read the report

A run writes one Markdown file, named after the time it started, and prints the same text when it ends. It is the only file a run leaves; the lock and a temporary copy of the report exist only while it runs.

The report starts with the Tyler version, the start time, the number of cases and repeats, and how far the run got. Each model then has a table, followed by a list of the chats that failed or were not fully correct, with the saved and expected values that differ. A failed chat is listed with its failure and with any record it got wrong before it failed.

Saved values come from the model, so the report escapes every punctuation mark in them: they show as written, and nothing in them can act as a link, an image, HTML or a table border. In the Markdown source and the terminal they appear with backslashes, such as `2026\-11\-11`.

Each table row shows the value with verification off, with it on, and the change.

Every case and repeat runs once in each mode. A chat is measured only when it finished in both modes, so both columns cover the same chats and their totals can be compared.

| Row | Meaning |
|---|---|
| Chats run, Chats that failed | A chat that fails, for example on a rate limit, is counted here and left out of every row below. The providers do not retry. See [Failed chats](#failed-chats). |
| Chats compared | Chats that finished in both modes. Every row below is measured over these. |
| Fully correct chats (%) | Chats where every expected record was saved correctly and nothing else was saved. |
| Wrong saves | Saved records with a field outside tolerance, plus records that should not have been saved. |
| Missed items | Expected records that were not saved, including ones verification refused. |
| Median calorie error (%) | Over saved records that matched an expected one. Calories the user stated are left out. |
| Items saved after a rejection | Records verification rejected at least once and then accepted. As in verification itself, a check that returned no usable verdict counts as a rejection. |
| Items refused | Records verification rejected twice, so nothing was saved. |
| Model calls, tokens, cost | Averages per chat. They include the verification and regeneration calls inside `recordFood`. |
| Latency | Wall time per chat: the median and the 95th percentile. |
| Model calls that reported no usage | Calls whose response carried no token counts. Their tokens and cost are missing from the rows above. |

Wrong saves should fall with verification on. Missed items and cost will rise. Whether the check pays for itself is the comparison between those.

## How records are scored

Scoring uses no model. [scoring.py](scoring.py) pairs each expected record with a saved record whose name contains one of its accepted names as whole words, ignoring case. A plural also matches, so "Boiled eggs" matches `egg` and "Eggplant" does not. When several saved records qualify, the one with the fewest wrong fields is paired.

- **Amount and unit** must match exactly. Common spellings of a unit count as the same unit (`g`, `gram`, `grams`), and so does a plural (`item`, `items`). Neither is checked when the user gave no amount.
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
      "units": ["g"],
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
| `names` | Accepted food names. A saved name matches when it contains one of them as whole words. List a different word, such as `oatmeal` beside `oat`, separately. |
| `amount`, `units` | The amount and the accepted units, one spelling each. Use `null` and `[]` when the user gave no amount. |
| `nutrition` | Reference values for the expected amount. A nutrient you leave out is not checked. |
| `stated` | Optional. The nutrients the user stated, which must be saved exactly. |
| `source` | Where the reference values come from. Required. |
| `tolerance` | Optional `relative` and `absolute` values that replace the defaults for this record. |

Take reference values from [USDA FoodData Central](https://fdc.nal.usda.gov/) and give the FDC ID, or from the brand's own published values. Do not estimate them.

## Update the prices

[prices.json](prices.json) holds each model's prices per million tokens, the pricing page they came from, and the date they were checked. When prices change, edit the values and the `checked` date. A model that is not in the table still gets its tokens reported, with no cost. List only standard prices: a promotional price would be wrong once it ends.

Before a run starts, `prices.json` is checked against [prices.schema.json](prices.schema.json), and [models.json](models.json) against [models.schema.json](models.schema.json). A file that does not match, for example after a typo, stops the run with the file's name and the problem, before any call or report.

## Limits

- **Java's rules are not applied.** A stand-in answers the Java operations and keeps whatever `recordFood` sends. A record Java would reject, such as an impossible date, is scored as a wrong save.
- **Other tools are not answered.** Only the time, food and profile operations have a stand-in. A chat that reads or writes a file, or saves a workout plan, ends as a failed chat that names the operation.
- **Verification outcomes are inferred.** The harness counts the model calls inside each `recordFood` call. One call means the item was accepted straight away, and more than one means it was rejected at least once. This holds while verification checks one item per call.
- **Chats run one at a time**, including across providers, so a full run of several models takes a long time.
- **A fallback model is priced as the requested one.** Claude can serve a declined request with another model. Food messages should not trigger it.
- **Prices are the standard tier.** Long-prompt and off-peak rates are noted in `prices.json` and not applied.
