# Food recording eval harness

[Python development guide](../README.md) · [Food verification](../../docs/AGENT_OPERATIONS.md#food-verification)

This harness measures how accurately the agent records food and what that costs. It sends labelled messages to a real provider through the real agent, tools and `recordFood`'s checks, then compares the saved records with the expected ones. Each case runs with `recordFood`'s nutrition check off and on, so the report shows what that check changes. The name and count check and the date check run in both modes, because the app has no switch for them.

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

`--all-models` runs every model Settings suggests, for every provider that has a key. A provider without a key is skipped, and the run says so. A named `--model` whose provider has no key stops the run before any call.

The suggested models come from the backend's [llm-models.properties](../../src/main/resources/llm-models.properties), the same file Settings reads, so the harness always runs the models the app offers. To change them, edit that file.

To check a prompt or tool change at low cost, run one cheap model of each provider instead:

```powershell
uv run --locked python -m evals --cheap-models
```

`--cheap-models` runs each provider's `cheap` model in `llm-models.properties`: `gpt-6-luna`, `claude-haiku-5-5` and DeepSeek V4.1 Flash, whose model ID is `deepseek-flash`. It skips a provider without a key, as `--all-models` does.

What they cost:

- **`gpt-6-luna` and `claude-haiku-5-5`:** their input and output prices are a twentieth or less of the other OpenAI and Claude suggestions'.
- **DeepSeek V4.1 Flash:** it is DeepSeek's only suggestion. It costs $0.15 input and $0.60 output per million tokens, and twice that in DeepSeek's peak hours (see [Limits](#limits)).
- **A full run:** the [November 2026 eval](../../docs/evals/eval-harness/2026-11-12-food-recording.md) cost $19.81 for all eight models then suggested. Of that, `claude-haiku-5-5` cost about $0.25. The report priced DeepSeek V4.1 Flash at $0.16, at DeepSeek's peak price; at the off-peak price the same run is $0.08. That run had 39 cases.

To change the cheap models, edit the `llm.models.<provider>.cheap` entries in that file. A cheap model must be one of its provider's suggestions: the backend refuses to start otherwise, and a `--cheap-models` run stops before any call. A test checks that each cheap model has a price in `prices.json`, so a cheap run always reports its cost.

| Option | Meaning |
|---|---|
| `--model PROVIDER:MODEL` | A model to evaluate. Repeat it for several. |
| `--all-models` | Evaluate every suggested model of every provider that has a key. |
| `--cheap-models` | Evaluate one low-cost suggested model of every provider that has a key. Use one of `--model`, `--all-models` and `--cheap-models`. |
| `--save-key PROVIDER` | Ask for that provider's key, save it, and stop. |
| `--repeats N` | How many times each case runs in each mode. The default is 3, because model output varies. |
| `--case ID` | Run only this case. Repeat it for several. |
| `--category NAME` | Run only this category's cases. It combines with `--case`. |
| `--results DIRECTORY` | Where the Markdown report goes. The default is `evals/results/`, which git ignores. |
| `--keys DIRECTORY` | Where saved keys are read from and written to. The default is `Documents\.tylerTest`. |

A full run makes `cases × repeats × 2` chats for each model: 402 chats with the 67 shipped cases and the default 3 repeats. With all three keys, `--all-models` is seven models, so 2,814 chats, and `--cheap-models` is three, so 1,206 chats. A chat is two or more model calls, plus two for the name and count check and the date check, one for each correction they ask for, and, with the nutrition check on, one more for each food. Chats run one at a time, and each prints one progress line.

The report file is created before the first call and replaced after every chat, in one step, so it always holds a whole report. If it cannot be updated, for example while another program has it open, the run says so and goes on; the next chat writes it again. If you stop a run with Ctrl-C, the chats that finished are kept and reported.

### Failed chats

A chat that fails, for example on a rate limit, is counted and the run goes on. A model that reaches 10 failed chats while it still has chats to run is stopped: its remaining chats are skipped, the run goes on with the next model, and the report says which model was stopped. A model whose 10th failure is its last chat ran in full and is not stopped. A revoked key or a mistyped model ID would otherwise fail every chat of that model.

A model's chats run in case-file order, by file name, with the `many-items` category last. Its chats take the longest and are the likeliest to fail on the run deadline, so a model that is stopped there has already run every other category.

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

The report starts with the Tyler version, the start time, the number of cases and repeats, and how far the run got. Each model then has a table, followed by two lists:

- **Chats that failed or were not fully correct**, with the saved and expected values that differ. A failed chat is listed with its failure and with any record it got wrong before it failed.
- **What the checks changed**: one row for each thing a check did in a chat, with the check's tool, the food, the change and the check's reason. This is where to look for why a food was removed, added or corrected.

Saved values come from the model, so the report escapes every punctuation mark in them: they show as written, and nothing in them can act as a link, an image, HTML or a table border. In the Markdown source and the terminal they appear with backslashes, such as `2026\-11\-11`.

Each table row shows the value with the nutrition check off, with it on, and the change.

Every case and repeat runs once in each mode. A chat is measured only when it finished in both modes, so both columns cover the same chats and their totals can be compared.

| Row | Meaning |
|---|---|
| Chats run, Chats that failed | A chat that fails, for example on a rate limit, is counted here and left out of every row below. The providers do not retry. See [Failed chats](#failed-chats). |
| Chats compared | Chats that finished in both modes. Every row below is measured over these. |
| Fully correct chats (%) | Chats where every expected record was saved correctly and nothing else was saved. |
| Wrong saves | Saved records with a field outside tolerance, plus records that should not have been saved. |
| Missed items | Expected records that were not saved, including ones the checks removed. |
| Median calorie error (%) | Over saved records that matched an expected one. Calories the user stated are left out. |
| Records the checks removed | Records the name and count check took out of a call's list, so they were not saved. |
| Foods the checks added | Foods the name and count check found missing and had written. |
| Amounts corrected | Foods whose amount or unit the name and count check had written again. |
| Dates corrected | Foods whose date the date check corrected. |
| Nutrition values corrected | Foods whose nutrition values the nutrition check rejected and had written again. |
| Nutrition checks without a usable answer | Foods the nutrition check could not settle: the check, or the correction after it, gave no answer that could be used twice in a row. The food was saved with the values it had. |
| Model calls, tokens, cost | Averages per chat. They include the check and correction requests inside `recordFood`. |
| Latency | Wall time per chat: the median and the 95th percentile. |
| Model calls that reported no usage | Calls whose response carried no token counts. Their tokens and cost are missing from the rows above. |

Wrong saves should fall with the nutrition check on, and cost will rise. Whether the check pays for itself is the comparison between those.

Reports made before the checks were split (issue #113) are not directly comparable. In those, "off" meant no check at all and "on" meant one check of the whole record. Now the name and count check and the date check run in both modes.

## How records are scored

Scoring uses no model. [scoring.py](scoring.py) pairs each expected record with a saved record whose name contains one of its accepted names as whole words, ignoring case. A plural also matches, so "Boiled eggs" matches `egg` and "Eggplant" does not. A more specific name is paired first: the pair whose matching name has more words wins, so `fried rice` gets "Fried rice" before `rice` can take it. Among equally specific names, the saved record with the fewest wrong fields is paired.

- **Amount and unit** must match exactly. Common spellings of a unit count as the same unit (`g`, `gram`, `grams`), and so does a plural ending in "s" or "es" (`item`, `items`; `glass`, `glasses`). A unit converted from the user's, such as 240 ml for 1 cup, is wrong, and so is one with a conversion added, such as `cup (240 ml)`. When the user gave two amounts for one food, such as "one 2 oz bar", a record may use either, but not the amount of one with the unit of the other. Neither is checked when the user gave no amount.
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
| `amount`, `units` | The amount the user gave and the accepted units, one spelling each. Use `null` and `[]` when the user gave no amount. See [Accepted units](#accepted-units). |
| `alternatives` | Optional. Other amounts the user gave for the same food, each with `amount` and `units`, such as `[{"amount": 2, "units": ["oz"]}]` for "one 2 oz bar". The nutrition is the same for each. |
| `nutrition` | Reference values for the expected amount. A nutrient you leave out is not checked. |
| `stated` | Optional. The nutrients the user stated, which must be saved exactly. |
| `source` | Where the reference values come from. Required. |
| `tolerance` | Optional `relative` and `absolute` values that replace the defaults for this record. |

Take reference values from [USDA FoodData Central](https://fdc.nal.usda.gov/) and give the FDC ID. Do not estimate them.

The `branded` category is the exception: take its values from the brand's own published US nutrition, and give the page's address and the date you read it, as in `https://..., checked 2026-11-15`. A test checks that form. The `big-mac` and `mcdonalds-fries` cases still use FoodData Central SR Legacy entries, because McDonald's refuses automated reads of its pages; [#114](https://github.com/phanmi/tyler_agent/issues/114) re-sources them by hand. SR Legacy's restaurant entries can be older than the product. These are the sources Tyler tells the model to use for values the user does not state; see [Estimated nutrition values](../../docs/AGENT_OPERATIONS.md#estimated-nutrition-values). A brand can change a recipe or a portion, so check the page again before relying on an old value.

The `many-items` category has rules of its own. A case is one message that lists 3 to 8 foods, each with an amount. It measures whether every food is saved with its own amount, unit and values.

- Give the foods in a case different names, so each saved record pairs with its own expected record.
- Keep a case of every size from 3 to 8.
- Use foods whose amount settles their nutrition. Leave out a food whose values depend on a recipe or on the size of a piece, such as mashed potatoes, stuffing, a slice of bacon or a dinner roll. FoodData Central has more than one entry or piece weight for each, so no single reference value is fair.

The [many-foods baseline](../../docs/evals/many-items/2026-11-16-many-items.md) records how each provider's low-cost model did on this category. Compare a later run with it.

Do not add a case for a cooked dish named as a whole, such as egg fried rice. Recipes differ too much for one reference value to be fair: FoodData Central's own entries for one dish, such as lasagna with meat or spaghetti with meat sauce, differ by a quarter or more in calories. For the same reason, do not add a case for pizza, even from a named chain: crusts, sizes and toppings vary too much, and a slice's weight differs between sources.

### Accepted units

Tyler saves the amount and unit the user gave, in the user's own word; see [Amounts and units](../../docs/AGENT_OPERATIONS.md#amounts-and-units). A case accepts the same:

- **When the user names a unit**, such as `slice`, `cup`, `oz`, a Snickers `bar`, `medium` fries or a `grande` latte, accept only that word. The scorer already accepts its common spellings and plurals.
- **When the user only counts**, such as "a Big Mac" or "3 large eggs", accept the food's own words (`egg`, `large egg`) and the generic count words: `piece`, `item`, `serving`, `each`, `unit`, `count`, `whole` and `portion`. None of these adds a number the user did not say.
- **Never accept a converted unit**, such as `ml` for a cup or `g` for a medium fries.

The `uncommon-unit` category holds units outside the common weights and measures, such as a can, a stalk, a clove, a pat or an ear. Use only a unit that FoodData Central gives a weight for, in an SR Legacy or FNDDS portion, and take the reference values for that weight. FoodData Central has no weight for some household units, such as a glass, so they have no case. A unit whose weight depends on the brand, such as a scoop of protein powder, is a `branded` case instead: name the product in the message and take the brand's values for its scoop.

## Update the prices

[prices.json](prices.json) holds each model's prices per million tokens, the pricing page they came from, and the date they were checked. When prices change, edit the values and the `checked` date. A model that is not in the table still gets its tokens reported, with no cost. List only standard prices: a promotional price would be wrong once it ends.

Before a run starts, `prices.json` is checked against [prices.schema.json](prices.schema.json), and the model entries the run needs are read from `llm-models.properties`. A price file that does not match, for example after a typo, stops the run with the file's name and the problem, before any call or report. So does a missing model entry, or a `cheap` entry that is not one of its provider's suggestions.

## Limits

- **Java's rules are not applied.** A stand-in answers the Java operations and keeps whatever `recordFood` sends. A record Java would reject, such as an impossible date, is scored as a wrong save.
- **Other tools are not answered.** Only the time, food and profile operations have a stand-in. A chat that reads or writes a file, or saves a workout plan, ends as a failed chat that names the operation.
- **A check that changes nothing leaves no row.** The report lists what the checks changed and why. A food every check passed is not listed, so the report does not show what a check was asked about a food it accepted.
- **Chats run one at a time**, including across providers, so a full run of several models takes a long time.
- **A fallback model is priced as the requested one.** Claude can serve a declined request with another model. Food messages should not trigger it.
- **Prices are the standard tier.** Long-prompt rates are noted in `prices.json` and not applied.
- **DeepSeek is priced at its off-peak rate.** DeepSeek charges twice as much from 01:00 to 04:00 and from 06:00 to 10:00 UTC, Monday to Friday. A run in those hours costs up to twice what the report says.
