# Nutrition sources eval, November 2026

[Eval harness](../../agent-python/evals/README.md) · [Generated report, before](2026-11-16-nutrition-sources-before-report.md) · [Generated report, after](2026-11-16-nutrition-sources-after-report.md) · [Many-foods baseline](2026-11-16-many-items.md)

**This run measures [#105](https://github.com/phanmi/tyler_agent/issues/105).** With that change, the agent bases the nutrition values it estimates on the brand's published nutrition for packaged and restaurant food, and on USDA FoodData Central for other food. Values the user states are saved exactly as stated. Food verification judges estimates against the same sources and no longer rejects a value for differing slightly; see [Estimated nutrition values](../AGENT_OPERATIONS.md#estimated-nutrition-values).

The 62 shipped cases ran on the three low-cost models once before the change and once after it.

## Summary

- **Fully correct chats rose on two of the three models.**
  - `gpt-6-luna`: from 79.5% to 82.1% with verification off, and from 73.5% to 81.0% with it on.
  - `deepseek-flash`: from 72.0% to 79.5% off, and from 72.0% to 80.5% on.
  - `claude-haiku-5-5` fell from 84.3% to 82.8% off and from 81.1% to 80.6% on: 2 fewer fully correct chats off, and the same number on out of one more chat compared.
- **The median calorie error is not worse for any model or mode.** It was 0.3% to 0.4% before and 0.2% to 0.3% after.
- **Verification hurt much less on messages that list many foods.** With it on, `many-items` chats that were not fully correct fell from 27 to 13 on `gpt-6-luna` and from 33 to 18 on `deepseek-flash`. Two runs of the old code gave 25 and 27, and 31 and 33.
- **`gpt-6-luna` now estimates FoodData Central values for common foods.** Its chats with a nutrition value outside the tolerance fell from 11 to 4 with verification off and from 14 to 2 with it on. Before the change it saved bread at about 100 kcal a slice against 80.6, and a bowl of rice at 310 kcal against 205.4. After it, it did neither.
- **Stated values were kept.** Before the change, `deepseek-flash` saved a banana the user stated as 1,900 kcal at 105 kcal in 2 chats, and `claude-haiku-5-5` did not save that banana in 4. After it, no chat changed or dropped that banana.
- **Branded foods moved little.** On the four cases with brand-page references, chats with a nutrition value outside the tolerance fell from 4 to 2. Most branded misses are units, which [#104](https://github.com/phanmi/tyler_agent/issues/104) covers. Pizza Hut slices still miss their SR Legacy reference, as expected.
- **Some things got worse.** See [What got worse](#what-got-worse).
  - With verification off, `claude-haiku-5-5` saved two foods or a whole snack as one record in 5 chats, against none before.
  - With verification on, `gpt-6-luna` dropped chicken breast with a stated 220 kcal in 2 chats.
  - With verification on, 4 branded foods were not saved, against 1 before.

## What was run

| | |
|---|---|
| Date | 2026-11-16. Before: 05:26 to 11:43 UTC. After: 11:58 to 14:43 UTC. |
| Before | `main` after [PR #112](https://github.com/phanmi/tyler_agent/pull/112), with the `branded` cases of this change copied in, so that both runs score the same cases. |
| After | The #105 change: the `recordFood` description in `definition.json`, and the verification and correction prompts in `verification.py`. Only these two files differ between the runs. |
| Cases | All 62 shipped cases in 10 categories. Four of the 7 `branded` cases take their reference from the brand's own page: Snickers, the Chick-fil-A Chicken Sandwich, a Starbucks grande Caffè Latte and a Chocolate Chip Clif Bar. The Big Mac, medium McDonald's fries and Pizza Hut slices keep their SR Legacy reference for now. |
| Repeats | Each case 3 times with food verification off and 3 times with it on |
| Models | `gpt-6-luna`, `claude-haiku-5-5` and `deepseek-flash` (DeepSeek V4.1 Flash): each provider's low-cost model, which `--cheap-models` selects |
| Chats | 1,116 in each run, or 186 for each model and mode. 2 chats failed before and 3 after; see [Other findings](#other-findings). |

Both generated reports show version 0.2.1, the release version in the build configuration; the code is newer than that release.

A chat is **fully correct** when every food the case expects is saved with the right name, amount, unit, date and nutrition, and nothing else is saved. Calories pass within 20% or 15 kcal of the reference, whichever is wider, and protein, carbs, fat and fiber within 20% or 2 g. The [harness README](../../agent-python/evals/README.md#read-the-report) defines every measure.

## Results

Each cell is before → after. Each model is measured over the chats that finished in both modes, shown as **Compared**. Wrong saves and missed items are counts of food records over those chats.

### Verification off

| Model | Compared | Fully correct (%) | Wrong saves | Missed items | Median calorie error (%) | Cost per chat (USD) |
|---|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 185 → 184 | 79.5 → 82.1 | 37 → 30 | 34 → 21 | 0.3 → 0.3 | 0.00046 → 0.00052 |
| `claude-haiku-5-5` | 185 → 186 | 84.3 → 82.8 | 34 → 37 | 1 → 9 | 0.4 → 0.3 | 0.00108 → 0.00116 |
| `deepseek-flash` | 186 → 185 | 72.0 → 79.5 | 84 → 69 | 2 → 3 | 0.3 → 0.3 | 0.00039 → 0.00041 |

### Verification on

| Model | Compared | Fully correct (%) | Wrong saves | Missed items | Refused | Saved after a rejection | Median calorie error (%) | Cost per chat (USD) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 185 → 184 | 73.5 → 81.0 | 39 → 27 | 42 → 25 | 12 → 11 | 23 → 26 | 0.3 → 0.2 | 0.00070 → 0.00078 |
| `claude-haiku-5-5` | 185 → 186 | 81.1 → 80.6 | 31 → 39 | 10 → 5 | 7 → 2 | 23 → 21 | 0.4 → 0.3 | 0.00211 → 0.00223 |
| `deepseek-flash` | 186 → 185 | 72.0 → 80.5 | 32 → 21 | 31 → 25 | 33 → 29 | 88 → 45 | 0.3 → 0.3 | 0.00078 → 0.00076 |

**Refused** counts records verification rejected twice and did not save. **Saved after a rejection** counts records it rejected once and then accepted.

### What went wrong in each chat

The table counts chats, before → after, over every chat the generated reports list, including chats left out of **Compared**. A chat with two kinds of problem is in two rows.

| What went wrong in the chat | `gpt-6-luna` off | on | `claude-haiku-5-5` off | on | `deepseek-flash` off | on |
|---|--:|--:|--:|--:|--:|--:|
| A food is missing | 13 → 10 | 23 → 21 | 1 → 5 | 10 → 5 | 2 → 3 | 29 → 22 |
| A record holds the whole meal | 10 → 4 | 16 → 6 | 0 → 2 | 3 → 4 | 0 → 0 | 6 → 6 |
| A date is wrong or missing | 4 → 7 | 0 → 0 | 0 → 0 | 0 → 0 | 31 → 19 | 1 → 1 |
| An amount or unit differs from the user's | 11 → 13 | 9 → 13 | 15 → 15 | 12 → 22 | 8 → 8 | 8 → 6 |
| A nutrition value is outside the tolerance | 11 → 4 | 14 → 2 | 11 → 15 | 12 → 13 | 5 → 5 | 13 → 6 |
| A value the user stated was changed | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 2 → 0 | 0 → 0 |
| A food was saved that the case does not expect | 0 → 0 | 0 → 1 | 0 → 0 | 0 → 1 | 6 → 2 | 5 → 3 |
| The chat failed | 0 → 1 | 1 → 1 | 0 → 0 | 1 → 0 | 0 → 1 | 0 → 0 |
| **Chats not fully correct or failed** | **38 → 35** | **50 → 36** | **30 → 32** | **36 → 36** | **52 → 39** | **52 → 36** |

As in the [many-foods baseline](2026-11-16-many-items.md#what-verification-changed), a record is counted as holding the whole meal when its unit is wrong and its calories are at least 1.8 times the reference for the food it is named for. Its other wrong fields are not counted again. A record for two slices of bread with peanut butter, at 1.7 times the reference for the peanut butter, falls under the amount and nutrition rows instead.

The date row is not something this change touches: the agent looks up today's date with a separate tool. `deepseek-flash` saved a wrong date in 31 chats before and 19 after with verification off, which shows how much one run can differ from another.

## Messages that list many foods

Chats in the `many-items` category that were not fully correct, out of 60 for each model and mode; a failed chat counts as not fully correct. The baseline is the [earlier run of the same cases](2026-11-16-many-items.md) on the old code.

| Model | Off: baseline | before | after | On: baseline | before | after |
|---|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 12 | 17 | 11 | 25 | 27 | 13 |
| `claude-haiku-5-5` | 12 | 17 | 17 | 16 | 17 | 20 |
| `deepseek-flash` | 21 | 15 | 14 | 31 | 33 | 18 |

- **How much the old code varies.** Between its two runs, the count moved by up to 6 chats with verification off and up to 2 with it on.
- **What changed with verification on.** The count fell by 14 chats on `gpt-6-luna` and by 15 on `deepseek-flash`, which is more than that. On `gpt-6-luna`, chats with a whole-meal record fell from 13 to 4 and chats with a missing food from 16 to 6. On `deepseek-flash`, chats with a missing food fell from 23 to 13.
- **Why it most likely changed.** The verifier rejected fewer records. Over all cases, `deepseek-flash` saved 45 records after a rejection against 88, and every model had fewer refusals. That fits the verifier's new instruction not to reject a value for differing slightly.
- **What is not known.** The run keeps no rejection reasons, so it cannot show which rejections went away. Making the reasons visible is the first point of the verification overhaul in [#113](https://github.com/phanmi/tyler_agent/issues/113).
- **`claude-haiku-5-5` did not improve here.** It went from 17 to 20 with verification on, close to the spread above.

Verification still drops foods. With it on, `deepseek-flash` refused 29 records and missed 25 items, against 3 missed items with it off. So [#113](https://github.com/phanmi/tyler_agent/issues/113) is still needed.

## Estimated values

- **`gpt-6-luna` moved to FoodData Central values.**
  - Before the change, it saved bread with too many calories or carbs, mostly at 100 kcal a slice against 80.6, in 5 chats with verification off and 4 with it on. It saved "a bowl of rice" at 310 kcal against 205.4 for the reference cup in 5 chats.
  - After it, neither happened. It saved the bowl of rice within the tolerance in 4 chats and saved nothing for it in 2.
- **`claude-haiku-5-5` did not move.** It saved a slice of Swiss cheese at 80 kcal against 110.0 in all 3 chats with verification off, both before and after, and 120 g of 90% lean ground beef at 211 kcal against 276.0 in 1 chat before and 2 after.
- **`deepseek-flash` improved with verification on.** Its chats with a nutrition value outside the tolerance fell from 13 to 6. One chat before saved two Pizza Hut slices at 1,360 kcal against 657.7.

## Stated values

These are the `stated-nutrition` cases, in which the user gives the values to save.

- **A stated value was replaced before the change and not after.** For "I ate a banana. Record it as 1900 kcal, that is what my tracker says.", `deepseek-flash` saved 105 kcal in 2 chats with verification off before the change, and in none after it.
- **A stated value was refused before the change and not after.** `claude-haiku-5-5` saved nothing for the same banana in 4 chats before, 3 of them with verification on, and saved it in every chat after.
- **One case got worse.** See [What got worse](#what-got-worse).

## Branded foods

Chats with a nutrition value outside the tolerance, before → after:

| Cases | `gpt-6-luna` off | on | `claude-haiku-5-5` off | on | `deepseek-flash` off | on |
|---|--:|--:|--:|--:|--:|--:|
| The four with brand-page references | 0 → 0 | 0 → 0 | 0 → 1 | 1 → 0 | 1 → 0 | 2 → 1 |
| Pizza Hut slices | 3 → 3 | 3 → 1 | 0 → 0 | 0 → 0 | 2 → 2 | 3 → 2 |

- **The brand-page cases.** The misses were a Chick-fil-A sandwich at 11 g of fat against 16, in one `claude-haiku-5-5` chat in each run, and a Starbucks latte's fat or carbs on `deepseek-flash`.
- **Pizza Hut slices.** They were saved with 32 to 34 g of protein or 36 to 42 g of fat, against the SR Legacy reference of 26.0 g and 29.6 g. That matches today's larger slice; see the [first eval report](2026-11-12-food-recording.md#3-prefer-usda-fooddata-central-for-nutrition-values). Re-sourcing this case and the two McDonald's cases from the brands' pages is left for a follow-up.
- **Units cause most branded misses, in both runs.**
  - "a regular 2 oz Snickers bar" was saved as 2 oz.
  - A grande latte was saved as 16 fl oz.
  - Medium fries were saved as 111 or 117 g.
  - A Clif Bar was saved as "bar (68 g)" or 68 g.
  - Keeping the user's unit is [#104](https://github.com/phanmi/tyler_agent/issues/104).

## What got worse

- **`claude-haiku-5-5` saved whole messages as one record more often with verification off.**
  - "2 slices of whole wheat bread with 2 tablespoons of smooth peanut butter" became one peanut butter record of 326 kcal in all 3 chats, against none before.
  - The four-food yogurt snack became one record of 309 kcal in 2 chats, against none before.
  - `gpt-6-luna` already did both in the old code, and still does. The overhaul in #113 is meant to stop such records.
- **`claude-haiku-5-5` changed more units with verification on.** It had 22 such chats against 12. The Clif Bar and the bread with peanut butter account for 5 of the 10 more; the rest are units such as "medium piece" for a bell pepper or an orange.
- **`gpt-6-luna` dropped a food with stated calories.** For "I ate 200 g of grilled chicken breast. The package says that is 220 kcal, so use that.", it saved nothing in 2 of 3 chats with verification on, against none before. The report does not say whether verification refused it.
  - Refusing a value the user stated goes against the rule that stated values always win.
  - Before the change, the same happened on `claude-haiku-5-5` for the stated 1,900 kcal banana, so it may be noise.
- **More branded foods were not saved with verification on.** 2 Pizza Hut chats and 1 Clif Bar chat on `gpt-6-luna`, and 1 Snickers chat on `deepseek-flash`, against 1 Chick-fil-A chat before.
- **Each chat costs a little more.** With verification off, input tokens per chat rose by about 250 to 410 and cost per chat by 5% to 13%. The longer `recordFood` description, which is sent with every model call, is the likely reason.

## Other findings

1. **Five chats failed, none for a reason this change touches.**
   - Before: a `gpt-6-luna` chat asked for a file read the harness does not provide (`unexpected operation: file.read`), and a `claude-haiku-5-5` chat for seven snacks hit the 300-second limit.
   - After: two `gpt-6-luna` chats ended with a provider failure, and a `deepseek-flash` chat asked for a file read.
2. **Vague amounts were left unsaved more often.** `gpt-6-luna` and `deepseek-flash` saved nothing for "some almonds" in 8 of their 12 chats before and in all 12 after. The report keeps no replies; the [first eval report](2026-11-12-food-recording.md#2-ask-the-user-for-missing-details-before-saving) found asking for the amount the likely reason. [#106](https://github.com/phanmi/tyler_agent/issues/106) is to make that deliberate.

## Cost

The reports' estimates are about $1.03 for the run before and $1.09 for the run after: cost per chat times 186 chats for each model and mode. The providers' bills were not checked against them.

DeepSeek charges twice its off-peak rate from 06:00 to 10:00 UTC on a weekday. The run before overlapped those hours and the run after did not. The report prices DeepSeek at the off-peak rate, so the real cost of the run before may be higher.

## Limits of this run

- **One run each.** Each model ran 186 chats in each mode, so one chat is worth about 0.5 points. A change of a few chats is within noise. The `many-items` comparison above is the only one with a second run of the old code to compare against.
- **Rejection reasons are not kept.** The report shows what was saved and how many records were rejected, not why. So the cause of the verification changes is inferred, not shown.
- **Only the low-cost models ran**, as decided for this issue. `gpt-6-sol`, `claude-sonnet-5-5` and `deepseek-flash` are the default models; only the last ran here.
- **Three branded references are out of date.** The Big Mac, medium fries and Pizza Hut slices still use SR Legacy values, which are older than today's products.
- **The agent cannot look values up.** The instruction steers the model toward values it remembers. A lookup tool for FoodData Central or brand data is a separate decision.
- **Replies are not kept.** The report shows what was saved, not what the agent told the user about a food it did not save.

## Appendix

The generated reports, [before](2026-11-16-nutrition-sources-before-report.md) and [after](2026-11-16-nutrition-sources-after-report.md), have every measure for each model and list every chat that failed or was not fully correct, with the saved and expected values that differ.
