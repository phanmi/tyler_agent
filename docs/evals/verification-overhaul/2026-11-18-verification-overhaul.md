# Verification overhaul eval, November 2026

[Eval harness](../../../agent-python/evals/README.md) · [Full generated report](2026-11-18-verification-overhaul-report.md) · [Plan and decisions](../../records/VERIFICATION_OVERHAUL.md) · Baselines: [many foods](../many-items/2026-11-16-many-items.md), [units](../units/2026-11-17-units.md), [nutrition sources](../nutrition-sources/2026-11-16-nutrition-sources.md)

**This run measures the new food checks of [#113](https://github.com/phanmi/tyler_agent/issues/113).** The one verifier of [#96](https://github.com/phanmi/tyler_agent/issues/96) made every low-cost model worse on messages that list many foods. [PR #117](https://github.com/phanmi/tyler_agent/pull/117) replaces it with three separate checks inside `recordFood`: a name and count check, a date check and a content check. `recordFood` now takes every food of a message in one call. [Food verification](../../architecture/AGENT_OPERATIONS.md#food-verification) describes the checks.

The three low-cost models ran all 67 cases. Each case is compared with its newest earlier run.

This document also records the three decisions the maintainer made from the run, and the reasons for each. See [Decisions](#decisions).

## Summary

- **Every low-cost model improved.** On the 65 cases that have a baseline, fully correct chats with the nutrition check on, against the baseline with no verification:
  - `gpt-6-luna`: from 75.9% to 95.9%.
  - `claude-haiku-5-5`: from 90.3% to 93.8%.
  - `deepseek-flash`: from 72.7% to 96.9%.
- **The new checks also beat the old verifier.** Against the baseline with verification on, the gains are 6.2, 2.0 and 10.8 points.
- **Messages that list many foods are no longer made worse.** The old verifier lowered these chats to 58.3%, 73.3% and 47.5% fully correct. They are now at 95.0%, 90.0% and 93.2%, above the 80.0%, 80.0% and 64.4% of the baseline with no verification.
- **No date was wrong.** The baseline with no verification had a wrong or missing date in 34 chats on `gpt-6-luna` and 42 on `deepseek-flash`. This run has none in 1,206 chats. The date check corrected 595 dates.
- **No listed food was dropped.** In the many-foods baseline the old verifier left a food out of 10, 4 and 24 chats. In this run no chat of that category missed a food.
- **The content check helped one model.** It raised `claude-haiku-5-5` by four chats. It made no difference on `gpt-6-luna`, where it rejected 40 foods, often with a reason that calls the values plausible.
- **The name and count check changed a record in 4 of 1,206 chats.** It cost one model request in every `recordFood` call. The maintainer switched it off after this run, and [#118](https://github.com/phanmi/tyler_agent/issues/118) is to show what it is worth.
- **A chat costs about what it did with the old verifier on, and the slowest chats take about half as long.**
- **The maintainer marked the run a temporary success,** and set the `some-almonds` case aside when reading it.
- **The run cost about $1.28** by the report's estimates.

## What was run

| | |
|---|---|
| Date | 2026-11-18, from 02:27 to about 04:59 UTC |
| Code | The checks of [PR #117](https://github.com/phanmi/tyler_agent/pull/117) with all three checks running: name and count, date, and content. The content check is shown the conversation and the run's tool results, and a food with no date is always corrected. Both of those came from a first run of 414 chats earlier the same day. The name and count check was switched off after this run. |
| Cases | All 67, in 11 categories |
| Repeats | Each case 3 times with the nutrition check off and 3 times with it on |
| Models | `gpt-6-luna`, `claude-haiku-5-5` and `deepseek-flash` (DeepSeek V4.1 Flash): each provider's low-cost model, which `--cheap-models` selects |
| Chats | 1,206, or 402 for each model. All ran, and two failed; see [Other findings](#other-findings). |

The generated report shows version 0.2.1, the release version in the build configuration; the code is newer than that release.

A chat is **fully correct** when every food the case expects is saved with the right name, amount, unit, date and nutrition, and nothing else is saved. Calories pass within 20% or 15 kcal of the reference, whichever is wider, and protein, carbs, fat and fiber within 20% or 2 g. The [harness README](../../../agent-python/evals/README.md#read-the-report) defines every measure.

**What "off" and "on" mean has changed.**

- **In this run** the switch is the nutrition check, which is the content check. The name and count check and the date check ran in every chat, with the switch off and on.
- **In the baselines** "off" is no verification at all, and "on" is the old verifier: one check of the whole record.

So this run's "off" column is not a run without checks. To see what the checks do, compare both of its columns with the baselines' "off".

## Results

Each model is measured over the chats that finished in both modes, shown as **Compared**. Wrong saves and missed items are counts of food records over those chats.

| Model | Compared | Fully correct, check off (%) | Check on (%) | Wrong saves, off / on | Missed items, off / on | Chats that failed |
|---|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 201 | 94.5 | 94.5 | 7 / 7 | 4 / 4 | 0 |
| `claude-haiku-5-5` | 201 | 91.5 | 93.5 | 28 / 14 | 1 / 1 | 0 |
| `deepseek-flash` | 199 | 95.5 | 97.0 | 7 / 6 | 2 / 0 | 2 |

### Fully correct chats by category

Each cell is fully correct chats of the chats compared.

| Category | Cases | `gpt-6-luna` off | on | `claude-haiku-5-5` off | on | `deepseek-flash` off | on |
|---|--:|--:|--:|--:|--:|--:|--:|
| `amount-and-unit` | 5 | 15 of 15 | 15 of 15 | 15 of 15 | 15 of 15 | 15 of 15 | 15 of 15 |
| `branded` | 7 | 21 of 21 | 20 of 21 | 19 of 21 | 18 of 21 | 19 of 21 | 20 of 21 |
| `correction` | 3 | 8 of 9 | 9 of 9 | 9 of 9 | 9 of 9 | 9 of 9 | 9 of 9 |
| `household-unit` | 6 | 17 of 18 | 16 of 18 | 18 of 18 | 18 of 18 | 18 of 18 | 18 of 18 |
| `many-items` | 20 | 55 of 60 | 57 of 60 | 49 of 60 | 54 of 60 | 56 of 59 | 55 of 59 |
| `no-amount` | 4 | 9 of 12 | 9 of 12 | 11 of 12 | 11 of 12 | 10 of 12 | 12 of 12 |
| `record-nothing` | 4 | 12 of 12 | 12 of 12 | 12 of 12 | 12 of 12 | 12 of 12 | 12 of 12 |
| `relative-date` | 5 | 15 of 15 | 15 of 15 | 15 of 15 | 15 of 15 | 14 of 14 | 14 of 14 |
| `several-items` | 4 | 12 of 12 | 11 of 12 | 11 of 12 | 11 of 12 | 12 of 12 | 12 of 12 |
| `stated-nutrition` | 4 | 12 of 12 | 12 of 12 | 12 of 12 | 12 of 12 | 10 of 12 | 12 of 12 |
| `uncommon-unit` | 5 | 14 of 15 | 14 of 15 | 13 of 15 | 13 of 15 | 15 of 15 | 14 of 15 |
| **All** | **67** | **190 of 201** | **190 of 201** | **184 of 201** | **188 of 201** | **190 of 199** | **193 of 199** |

All 10 `no-amount` chats that were not fully correct are the `some-almonds` case; see [Decisions](#decisions).

## Improvement over the baselines

### The 65 cases that have a baseline

Each case is compared with its newest earlier run on the low-cost models:

- **The 20 `many-items` cases and 22 unit cases:** the [units run](../units/2026-11-17-units.md) of 2026-11-17.
- **The other 23 cases:** the run after the change in the [nutrition-sources eval](../nutrition-sources/2026-11-16-nutrition-sources.md) of 2026-11-16.
- **Left out:** `some-almonds`, which the maintainer set aside, and `dymatize-iso100-scoop`, which is new and has no baseline.

Fully correct chats, in percent and as chats of the chats compared:

| Model | Baseline, no verification | Baseline, old verifier on | This run, nutrition check off | This run, nutrition check on |
|---|--:|--:|--:|--:|
| `gpt-6-luna` | 75.9 (148 of 195) | 89.7 (175 of 195) | 95.9 (187 of 195) | 95.9 (187 of 195) |
| `claude-haiku-5-5` | 90.3 (176 of 195) | 91.8 (179 of 195) | 91.8 (179 of 195) | 93.8 (183 of 195) |
| `deepseek-flash` | 72.7 (141 of 194) | 86.1 (167 of 194) | 96.4 (186 of 193) | 96.9 (187 of 193) |

The gain with the nutrition check on, in points:

| Model | Over no verification | Over the old verifier on | Chats not fully correct: no verification → old verifier on → now |
|---|--:|--:|--:|
| `gpt-6-luna` | +20.0 | +6.2 | 47 → 20 → 8 |
| `claude-haiku-5-5` | +3.5 | +2.0 | 19 → 16 → 12 |
| `deepseek-flash` | +24.2 | +10.8 | 53 → 27 → 6 |

- **`gpt-6-luna` and `deepseek-flash` gained the most,** and nearly all of it with the nutrition check off. That is the work of the date check and of `recordFood` taking a list; see [What went wrong in each chat](#what-went-wrong-in-each-chat).
- **`claude-haiku-5-5` gained the least.** It saved no wrong date in any run, so the date check had nothing to correct. Its gain comes from the content check.
- **With all 66 cases** other than `some-almonds`, the nutrition check on gives 96.0% (190 of 198), 93.9% (186 of 198) and 96.9% (190 of 196).

### What went wrong in each chat

The same 65 cases. Each cell is baseline → this run, and counts chats. A chat with two kinds of problem is in two rows. In the baseline columns "off" is no verification and "on" is the old verifier.

| What went wrong in the chat | `gpt-6-luna` off | on | `claude-haiku-5-5` off | on | `deepseek-flash` off | on |
|---|--:|--:|--:|--:|--:|--:|
| A food is missing | 3 → 1 | 12 → 1 | 3 → 0 | 2 → 0 | 0 → 0 | 18 → 0 |
| A date is wrong or missing | 34 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 42 → 0 | 2 → 0 |
| An amount or unit differs from the user's | 2 → 0 | 3 → 0 | 3 → 1 | 4 → 0 | 3 → 2 | 3 → 1 |
| A nutrition value is outside the tolerance | 11 → 7 | 10 → 7 | 19 → 15 | 16 → 12 | 3 → 5 | 4 → 5 |
| A food was saved that the case does not expect | 4 → 0 | 1 → 0 | 0 → 0 | 0 → 0 | 8 → 0 | 4 → 0 |
| The chat failed | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 1 → 0 | 0 → 2 |
| **Chats not fully correct or failed** | **47 → 8** | **20 → 8** | **19 → 16** | **16 → 12** | **54 → 7** | **27 → 8** |

- **Dates.** With no verification, `gpt-6-luna` and `deepseek-flash` saved a wrong or missing date in 34 and 42 chats. Now the date check runs in every chat, and no chat has one.
- **Missing foods.** The old verifier refused records, so with it on `gpt-6-luna` left a food out of 12 chats and `deepseek-flash` out of 18. One chat is left, and it saved nothing at all.
- **Foods the case does not expect.** The baselines had 17 chats that saved one, such as a food saved twice. This run has none.
- **Nutrition values are now most of what is left.** They are 51 of the 59 chats in the bottom row.

The report cannot say which change removed which problem. Between the baselines and this run the checks were replaced, and `recordFood` started taking a list.

### Messages that list many foods

The 20 `many-items` cases, 60 chats for each model and mode. Fully correct chats (%). The first two columns are the [many-foods baseline](../many-items/2026-11-16-many-items.md), which the [plan](../../records/VERIFICATION_OVERHAUL.md#what-counts-as-done) names as the result to beat.

| Model | Baseline 2026-11-16, no verification | Old verifier on | Units run 2026-11-17, no verification | Old verifier on | This run, nutrition check off | On |
|---|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 80.0 | 58.3 | 68.3 | 91.7 | 91.7 | 95.0 |
| `claude-haiku-5-5` | 80.0 | 73.3 | 85.0 | 86.7 | 81.7 | 90.0 |
| `deepseek-flash` | 64.4 | 47.5 | 65.0 | 76.7 | 94.9 | 93.2 |

- **Each model's best result in five runs** is this run with the nutrition check on, except `deepseek-flash`, whose best is this run with it off, one chat higher.
- **No food was missed and no unit was changed** in any of these 358 chats. So no record held the whole meal. In the baseline, with the old verifier on, 12, 3 and 6 chats had such a record, and 10, 4 and 24 chats missed a food.
- **What is left is nutrition values.** Every chat in this category that was not fully correct had a value outside the tolerance, apart from one chat that failed.

### Unit cases

The 22 cases of `amount-and-unit`, `household-unit`, `branded` and `uncommon-unit` that the units run shares with this one. Its generic scoop case was replaced and its Pizza Hut case removed after it ran.

| Model | Units run, no verification | Old verifier on | This run, nutrition check off | On |
|---|--:|--:|--:|--:|
| `gpt-6-luna` | 71.2 (47 of 66) | 92.4 (61 of 66) | 97.0 (64 of 66) | 93.9 (62 of 66) |
| `claude-haiku-5-5` | 89.4 (59 of 66) | 93.9 (62 of 66) | 93.9 (62 of 66) | 92.4 (61 of 66) |
| `deepseek-flash` | 68.2 (45 of 66) | 90.9 (60 of 66) | 97.0 (64 of 66) | 97.0 (64 of 66) |

With the nutrition check on, every model beats its result with no verification. The margin over the old verifier is small: one chat more on `gpt-6-luna`, one fewer on `claude-haiku-5-5`, and four more on `deepseek-flash`.

### The other 23 cases

`correction`, `no-amount` without `some-almonds`, `record-nothing`, `relative-date`, `several-items` and `stated-nutrition`.

| Model | Nutrition-sources run, no verification | Old verifier on | This run, nutrition check off | On |
|---|--:|--:|--:|--:|
| `gpt-6-luna` | 87.0 (60 of 69) | 85.5 (59 of 69) | 98.6 (68 of 69) | 98.6 (68 of 69) |
| `claude-haiku-5-5` | 95.7 (66 of 69) | 94.2 (65 of 69) | 98.6 (68 of 69) | 98.6 (68 of 69) |
| `deepseek-flash` | 83.8 (57 of 68) | 89.7 (61 of 68) | 97.1 (66 of 68) | 100.0 (68 of 68) |

That baseline was scored with the unit rules from before [#104](https://github.com/phanmi/tyler_agent/issues/104), which accepted generic words such as "serving". So its figures are not lower than today's rules would give.

## What each check did

Records changed, over the chats compared. Each cell is nutrition check off / on.

| Check | What it changed | `gpt-6-luna` | `claude-haiku-5-5` | `deepseek-flash` |
|---|---|--:|--:|--:|
| Name and count | Records removed | 0 / 1 | 0 / 0 | 0 / 0 |
| Name and count | Foods added | 0 / 0 | 0 / 0 | 0 / 0 |
| Name and count | Amounts or units written again | 0 / 0 | 0 / 0 | 1 / 2 |
| Date | Dates corrected | 98 / 78 | 0 / 0 | 193 / 226 |
| Content | Nutrition values corrected | 40 | 16 | 2 |

The generated report lists every change with the check's reason, under "What the checks changed".

### The date check

- **It corrected 595 dates,** all on `gpt-6-luna` and `deepseek-flash`. That is a date in about a fifth of `gpt-6-luna`'s chats and about half of `deepseek-flash`'s.
- **32 of `deepseek-flash`'s records had no date at all.** Python sends such a food to the date correction whatever the check answers. In the first run of the day, before that rule, two foods were saved with no date.
- **No chat ended with a wrong or missing date.**
- **`claude-haiku-5-5` needed no correction.** The check still cost it one request in every `recordFood` call.

### The content check

- **`claude-haiku-5-5`: a real gain.**
  - It corrected 16 foods in 9 chats, and 7 of those chats ended fully correct.
  - Ten of the foods had 0 for every nutrition value; see [Other findings](#other-findings).
  - Its wrong saves fell from 28 to 14.
- **`gpt-6-luna`: many corrections and no gain.**
  - It rejected 40 foods in 36 chats, and 34 of those chats were fully correct afterwards. The result with the check on equals the result with it off.
  - About half of its reasons end by calling the values plausible, or name a difference of a tenth of a gram or so. The prompt says neither is a reason to reject. One reason for a cup of whole milk ends "correction: all values are plausible". Another rejects 0.6 g of carbs in sour cream because "it should be about 0.5 g".
  - 14 of the 40 reasons note that the user did not state the values. The check is told to respect a value the user stated. These answers read as if a value the user did not state were suspect. That reading is not confirmed.
  - One right value was made wrong: a slice of pumpkin pie at 323 kcal, the reference value, was rewritten to 243.
- **`deepseek-flash`: two corrections.** One doubled the values of two rice cakes that were right, and the other chat ended fully correct. So its higher result with the check on is not the check's work. The chats that differ went wrong in the chat model's own records.
- **Stated values were respected.** In `stated-nutrition`, all 36 chats with the check on were fully correct, and no check rejected the banana the user gave as 1900 kcal. With the check off, `deepseek-flash` saved 105 kcal for it in 2 chats. No check corrected that: in the chats with the check on, the chat model wrote 1900 itself.
  - `gpt-6-luna`'s check rejected a chicken breast in 2 chats where the user had stated its calories. Both reasons keep the stated calories and question the protein, which the user did not state. Both chats ended fully correct.

### The name and count check

- **It ran in every `recordFood` call,** which is about one model call in five with the nutrition check off and one in seven with it on.
- **It changed a record in 4 chats of 1,206.**
  - **One removal,** on `gpt-6-luna`: a second `recordFood` call for a food an earlier call had already saved. The removal was right.
  - **Three amounts or units written again,** all on `deepseek-flash`: a grande latte written as 16 fl oz, almonds written as "1 snack", and a large egg whose unit the check said should be "egg".
  - **No food added.**
- **It passed four records it is meant to catch.** `deepseek-flash` saved the grande latte as 16 fl oz or 16 oz in 3 chats, and `claude-haiku-5-5` saved a Snickers bar with the unit "bar (2 oz)" in 1.
- **The chat models hardly made the mistakes it is for.** No record held a whole meal, and every chat that missed a food saved nothing at all.

## Cost and time

The closest earlier run in its mix of cases is the run after the change in the nutrition-sources eval of 2026-11-16. It has 61 of this run's 67 cases. Each cell is that run → this run.

| Model | Model calls per chat, off | on | Cost per chat (USD), off | on | Median reply (s), off | on | 95th percentile (s), off | on |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 2.78 → 4.70 | 5.33 → 7.02 | 0.00052 → 0.00061 | 0.00078 → 0.00083 | 5.5 → 6.8 | 7.8 → 8.6 | 11.0 → 10.8 | 26.5 → 14.8 |
| `claude-haiku-5-5` | 2.87 → 4.74 | 5.34 → 6.97 | 0.00116 → 0.00146 | 0.00223 → 0.00217 | 5.3 → 6.4 | 10.0 → 8.9 | 11.8 → 11.5 | 43.4 → 16.1 |
| `deepseek-flash` | 2.74 → 4.74 | 5.73 → 6.94 | 0.00041 → 0.00052 | 0.00076 → 0.00076 | 3.8 → 5.9 | 5.3 → 7.3 | 6.1 → 8.2 | 18.0 → 9.8 |

- **With the nutrition check on, a chat costs about what it did with the old verifier on:** 6% more on `gpt-6-luna`, 3% less on `claude-haiku-5-5`, and the same on `deepseek-flash`.
- **The slowest chats take about half as long.** The 95th percentile fell from 26.5 to 14.8 s, from 43.4 to 16.1 s and from 18.0 to 9.8 s. The content checks for a message's foods now run at the same time, and each check and correction runs once.
- **With the nutrition check off, a chat costs 17% to 27% more than with no verification,** and the median reply takes 1.1 to 2.1 s longer. In this run the name and count check and the date check ran in those chats.
- **Messages that list many foods.** The report does not split reply time by category. In the many-foods baseline the median `many-items` chat with the old verifier on took 16.4, 29.3 and 15.7 s. In this run, 95% of all chats with the nutrition check on finished within 14.8, 16.1 and 9.8 s.
- **The name and count check is now off,** so each `recordFood` call makes one request fewer than in this run. No run has measured that saving.

The case mix differs slightly between the two runs, and `recordFood`'s description grew with #104, so these are close comparisons and not exact ones.

**The run cost about $1.28** by the report's estimates. That is cost per chat times 201 chats for each model and mode:

- $0.29 for `gpt-6-luna`.
- $0.73 for `claude-haiku-5-5`.
- $0.26 for `deepseek-flash`.

The providers' bills were not checked against these. The run was outside DeepSeek's 06:00 to 10:00 UTC peak hours, so the report's off-peak price applies.

## Against what counts as done

The [plan](../../records/VERIFICATION_OVERHAUL.md#what-counts-as-done) lists what counts as done for #113. This run speaks to three of its items.

| Item | Result | Evidence |
|---|---|---|
| The low-cost models improve: with the nutrition check on, each beats its baseline result with no verification | Met | On `many-items`, 95.0, 90.0 and 93.2 against 80.0, 80.0 and 64.4. On the 22 unit cases, 93.9, 92.4 and 97.0 against 71.2, 89.4 and 68.2. The smallest margin is `claude-haiku-5-5` on the unit cases, 61 chats against 59 of 66. |
| No wrong or missing date | Met | None in 1,206 chats. |
| No missing food | Met on `many-items`. Not met elsewhere. | 12 chats missed a food: `some-almonds` in 10, and `gpt-6-luna`'s `chick-fil-a-sandwich` and `corrected-date` once each. Each saved nothing at all. |
| Better with the content check on than off, for each model | Not met | `gpt-6-luna` is level over the whole run. On `many-items`, `deepseek-flash` is one chat lower with the check on. On the unit cases, `gpt-6-luna` is two chats lower, `claude-haiku-5-5` one, and `deepseek-flash` level. |

- **About the missing foods.** In each of the 12 chats nothing was saved and no check removed a record. So the chat model most likely never called `recordFood`, and the checks, which run inside it, never ran. The report does not say whether `recordFood` was called, so this is not confirmed.
- **About the content check.** Most of these differences are one or two chats of 60 or 66, which is within the noise of one run. The item asks for a gain on every model, and the run does not show one.

The plan's other two items are about the app: every check tool implemented, with the content check switchable from Settings, and every #113 criterion met. The Settings switch, protocol version 3 and saving a message's foods in one transaction are not built yet.

## Decisions

The maintainer made three decisions from this run, on 2026-11-18. The plan records them under [Decisions](../../records/VERIFICATION_OVERHAUL.md#decisions), the third as D17. Under each decision below, the reason is the maintainer's, and the evidence is what this run shows for it.

### `some-almonds` is set aside

**The decision.** The run is read without the `some-almonds` case.

**The reason.** The message is "I snacked on some almonds this afternoon." It gives no amount, and it can be read as general chat that calls for no `recordFood`. So a chat that records nothing for it is not clearly wrong.

**The evidence.**

- **No check could have fixed the miss.** In all 10 chats that missed it, nothing was saved. The checks run inside `recordFood`, so they most likely never ran.
- **It is not a new problem.** The baseline run missed it in 12 of 18 chats: every chat of `gpt-6-luna` and `deepseek-flash`.
- **It hides the rest of the result.** It is 10 of the run's 12 chats with a missed food. Without it, two remain, both on `gpt-6-luna`.

**What it changes.** With the nutrition check on, fully correct chats are 96.0%, 93.9% and 96.9% over the other 66 cases, against 94.5%, 93.5% and 97.0% over all 67.

**Not decided.** Whether the case is reworded or removed. It is still in the eval, so the generated report counts it.

### The run is a temporary success

**The decision.** The run is marked a temporary success, and not the result that closes #113.

**The reason.** The maintainer called the run a partial success, with a big increase in the result itself. The evidence below is what the run shows on each side.

**The evidence.**

- **The gain is large.** Every low-cost model beats its baseline, by 20.0, 3.5 and 24.2 points over no verification. The two problems that started the overhaul are gone: no check dropped a food the user listed, and no record held a whole meal.
- **Dates are fixed.** No chat saved a wrong or missing date.
- **One item is not met.** The content check does not improve every model.
- **One check is not shown to work.** The run shows what the name and count check costs, and not that it does its job.
- **The work is not finished.** The app has no Settings switch yet, and no run has been made with the checks as they now stand.

### The name and count check is switched off

**The decision.** The check is switched off in every run (`RunSettings.check_names`, off by default) and then makes no model request. Its code and tests are kept. This is D17 in the plan.

**The reason.** The run proves that the check uses tokens, and not that it does important work. So it stays off until it is proved to work.

**The evidence.**

- **Its cost is known.** One model request in every `recordFood` call, on every model.
- **Its worth is not shown.** It changed a record in 4 of 1,206 chats, and passed four records it is meant to catch. On `claude-haiku-5-5` it changed nothing.
- **The eval cannot show its worth today.** The chat models hardly made the mistakes the check is for, so the current cases give it almost nothing to catch.
- **Those mistakes have other fixes now.** Records that hold a whole meal stopped with #104's unit rule; the [units run](../units/2026-11-17-units.md) had none. In the many-foods baseline, `claude-haiku-5-5` and `deepseek-flash` missed a food only with the old verifier on, and that verifier is gone.

**Why it is kept and not removed.** The check is implemented and tested. Switching it on needs no other change, so a later eval can run it.

**Why the proof is a separate issue.** Proving that the check works needs new eval cases that lead the chat models into the mistakes it is for: a food the user only mentions, a meal a model tends to save as one record, a long list with a food dropped, a unit a model tends to convert. Such cases are complex to design, and the maintainer decided that designing them is out of scope for #113. [#118](https://github.com/phanmi/tyler_agent/issues/118) holds that work. The check is switched on only after #118 shows that it works.

**What this means for #113.** The requirements that rest on this check are partially met: the check is implemented, and it does not run. They are the converted unit, a record that holds a whole meal, and a food that matches neither the conversation nor a tool result.

**What a run loses meanwhile.** Nothing after the chat model removes a record that does not belong, adds a food the message left out, or corrects an amount or unit. Going by this run, that is one food that would have been saved twice and three amounts or units, in 1,206 chats.

## Other findings

- **`claude-haiku-5-5` wrote 0 for every nutrition value in 5 chats.**
  - All were messages of two to eight foods: 3 of its 24 `several-items` chats and 2 of its 120 `many-items` chats.
  - With the nutrition check on, the content check corrected them in 3 chats. With it off, 2 chats saved them, 10 foods in all.
  - No earlier report has this. The one thing the chat model sees differently is that `recordFood` now takes a list. That this is the cause is not confirmed.
- **The grande latte is still converted.** `deepseek-flash` wrote 16 fl oz or 16 oz in 4 of 6 chats, and the name and count check corrected 1. With that check off, nothing corrects it.
- **Two chats failed.** Both are `deepseek-flash` calling a file tool, which the harness does not answer: `unexpected operation: file.read`. The run of 2026-11-16 has the same failure.
- **`gpt-6-luna` saves two slices of bread at 202 to 206 kcal** against 161.3, in 6 chats over 3 cases. Four of them had the nutrition check on, and the content check passed the value in each.

## Limits of this run

- **One run.** On 195 chats, one chat is worth about half a point, so a change of a few chats is within noise.
- **The name and count check was on.** The checks as they now stand, with that check off, have not been run.
- **The baselines differ in more than the checks.** The comparison for the 23 other cases also spans #104's unit change.
- **Whether `recordFood` was called is not shown.** A chat that saved nothing looks the same whether the chat model skipped the tool or called it with no food.
- **Reply time and cost are not split by category,** so the time a message with many foods takes is not measured directly.
- **Causes are not shown.** The report keeps each check's reason, but no transcript of the chat model's own requests.
- **Replies are not kept.** The report shows what was saved, not what the agent told the user.

## Appendix

The [generated report](2026-11-18-verification-overhaul-report.md) has every measure for each model. It lists every chat that failed or was not fully correct, with the saved and expected values that differ, and every change a check made, with its reason.
