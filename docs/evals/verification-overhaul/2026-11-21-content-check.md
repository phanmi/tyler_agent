# Content check eval, November 2026

[Eval harness](../../../agent-python/evals/README.md) · [Full generated report](2026-11-21-content-check-report.md) · [Plan and decisions](../../records/VERIFICATION_OVERHAUL.md) · Earlier runs: [many-foods baseline](../many-items/2026-11-16-many-items.md), [verification overhaul eval](2026-11-18-verification-overhaul.md)

**This run measures what the content check adds to a message that lists many foods.** The content check is the part of `recordFood` that asks a model whether a food's nutrition values are plausible, and has them written again when it says they are not. [Food verification](../../architecture/AGENT_OPERATIONS.md#food-verification) describes the checks of [#113](https://github.com/phanmi/tyler_agent/issues/113).

It is the first run with the name and count check off, which is how the app runs the checks. The three low-cost models ran the 20 `many-items` cases with the content check off and on.

The maintainer decided from this run that the content check is forced off in every chat of the app, and kept in the code. See [Decision](#decision).

## Summary

- **The first estimate is good enough.** With the content check off, 281, 269 and 280 of each model's 288 foods had every nutrition value within tolerance. The median calorie error was 0.2%, 0.4% and 0.3%.
- **The content check did not pay for itself.** With it on, fully correct chats were level on `gpt-6-luna`, four chats higher on `claude-haiku-5-5`, and three chats lower on `deepseek-flash`.
- **It made more correct foods wrong than it fixed.** It had 43 foods written again. 6 of those fixed a value outside tolerance, all on `claude-haiku-5-5`. 10 made a correct food wrong.
- **It passed 20 foods that had a value outside tolerance.**
- **It more than doubled the model requests in a chat.** A chat cost 1.6 to 2.0 times as much and took 1.2 to 3.7 seconds longer at the median.
- **No food was missed and no saved date was wrong,** in either mode on any model. The date check corrected 551 dates.
- **With the content check off, a message with many foods takes about as long as it did with no verification at all:** 8.3, 8.5 and 5.7 seconds at the median, against 7.7, 7.9 and 5.5.
- **Two of the three models beat their baseline with the check off.** `claude-haiku-5-5` is one chat below it.
- **Every model is lower than in the run of 2026-11-18 on these cases,** by 3.4 to 11.5 points. The cause is not known; see [Other findings](#other-findings).
- **The run cost about $0.55** by the report's estimates.

## What was run

| | |
|---|---|
| Date | 2026-11-21, from 18:24 to about 19:19 UTC |
| Code | The Python agent of [PR #121](https://github.com/phanmi/tyler_agent/pull/121). The name and count check is off, the date check runs in every chat, and the content check is off or on by mode. |
| Cases | The 20 `many-items` cases, with 96 expected records |
| Repeats | Each case 3 times with the content check off and 3 times with it on |
| Models | `gpt-6-luna`, `claude-haiku-5-5` and `deepseek-flash` (DeepSeek V4.1 Flash): each provider's low-cost model, which `--cheap-models` selects |
| Chats | 360, or 120 for each model. All ran and none failed. |

The generated report shows version 0.2.1, the release version in the build configuration; the code is newer than that release.

Each case is one message about today that lists 3 to 8 foods, each with an amount. A chat is **fully correct** when every food the case expects is saved with the right name, amount, unit, date and nutrition, and nothing else is saved. Calories pass within 20% or 15 kcal of the reference, whichever is wider, and protein, carbs, fat and fiber within 20% or 2 g. The [harness README](../../../agent-python/evals/README.md#read-the-report) defines every measure.

**What "off" and "on" mean.** The switch is the content check, which the report calls the nutrition check. The date check ran in both modes, and the name and count check in neither. So "off" is what a chat of the app does.

## Results

Each model has 60 chats in each mode, and 288 expected foods. Wrong saves and missed items are counts of food records.

| Model | Fully correct, check off (%) | Check on (%) | Wrong saves, off / on | Missed items, off / on | Chats that failed |
|---|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 88.3 (53 of 60) | 88.3 (53 of 60) | 7 / 7 | 0 / 0 | 0 |
| `claude-haiku-5-5` | 78.3 (47 of 60) | 85.0 (51 of 60) | 19 / 11 | 0 / 0 | 0 |
| `deepseek-flash` | 86.7 (52 of 60) | 81.7 (49 of 60) | 8 / 12 | 0 / 0 | 0 |

- **Every wrong save is a nutrition value outside the tolerance.** No food had a wrong name, amount, unit or date, and no chat saved a food its case does not expect.
- **Over the three models,** wrong saves went from 34 to 30 of 864 foods with the check on.

### Beside the earlier runs of these cases

Fully correct chats (%). "Off" and "on" mean something different in each run:

- **Baseline of 2026-11-16 and units run of 2026-11-17:** "off" is no verification at all, and "on" is the old verifier.
- **Run of 2026-11-18:** the content check off and on, with the name and count check and the date check running in both.
- **This run:** the content check off and on, with only the date check running in both.

| Model | Baseline, no verification | Old verifier on | Units run, no verification | Old verifier on | 2026-11-18, check off | Check on | This run, check off | Check on |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 80.0 | 58.3 | 68.3 | 91.7 | 91.7 | 95.0 | 88.3 | 88.3 |
| `claude-haiku-5-5` | 80.0 | 73.3 | 85.0 | 86.7 | 81.7 | 90.0 | 78.3 | 85.0 |
| `deepseek-flash` | 64.4 | 47.5 | 65.0 | 76.7 | 94.9 | 93.2 | 86.7 | 81.7 |

- **Against the baseline with no verification,** which the [plan](../../records/VERIFICATION_OVERHAUL.md#what-counts-as-done) names as the result to beat: with the check off, `gpt-6-luna` is 5 chats higher, 53 against 48 of 60, and `deepseek-flash` is at 52 of 60 against 38 of 59. `claude-haiku-5-5` is one chat lower, 47 against 48 of 60.
- **`claude-haiku-5-5` with the check off is below both of its results with no verification:** 78.3 against 80.0 and 85.0.
- **Against the baseline's old verifier,** every model is higher in both modes.
- **Against the units run with the old verifier on,** `gpt-6-luna` and `claude-haiku-5-5` are lower in both modes, and `deepseek-flash` is higher.
- **Against the run of 2026-11-18,** every model is lower in both modes, by 3.4 to 11.5 points. See [Other findings](#other-findings).

### What went wrong with the check off

These are the 34 wrong saves a chat of the app would have made. A food is counted once for each chat that saved it wrong.

| Food | Case | `gpt-6-luna` | `claude-haiku-5-5` | `deepseek-flash` |
|---|---|--:|--:|--:|
| Cooked 90% lean ground beef, 120 g | `six-items-taco-dinner` | 3 | 1 | 3 |
| Plain low-fat yogurt, 170 g | `seven-items-snacks` | 0 | 3 | 2 |
| Canned cranberry sauce, 60 g | `five-items-holiday-dinner` | 1 | 3 | 0 |
| Marinara sauce | `three-items-pasta-dinner` | 1 | 0 | 2 |
| Bread | `six-items-sandwich-lunch`, `six-items-brunch` | 2 | 0 | 1 |
| Swiss cheese, 1 slice | `six-items-sandwich-lunch` | 0 | 3 | 0 |
| Dark chocolate | `seven-items-snacks` | 0 | 3 | 0 |
| Turkey | `five-items-holiday-dinner`, `six-items-sandwich-lunch` | 0 | 3 | 0 |
| Cooked 90% lean ground beef, 100 g | `three-items-pasta-dinner` | 0 | 2 | 0 |
| Tortilla | `six-items-taco-dinner` | 0 | 1 | 0 |
| **All** | | **7** | **19** | **8** |

The taco beef went wrong on every model, in 7 of the 9 chats. `gpt-6-luna` saved too much fat and `claude-haiku-5-5` too few calories. `deepseek-flash` saved too little protein in 2 chats and too few calories in the third.

## What the content check did

Counts are foods, over the 60 chats of each model with the check on.

| | `gpt-6-luna` | `claude-haiku-5-5` | `deepseek-flash` | All |
|---|--:|--:|--:|--:|
| Foods it had written again | 28 | 7 | 8 | 43 |
| Of those: a value outside tolerance was fixed | 0 | 6 | 0 | 6 |
| Of those: a correct food was made wrong | 5 | 1 | 4 | 10 |
| Of those: the food stayed within tolerance | 23 | 0 | 4 | 27 |
| Foods with a value outside tolerance that it passed | 2 | 10 | 8 | 20 |

The wrong saves with the check on are the foods it made wrong and the foods it passed: 7, 11 and 12.

**How these were counted.** The report lists each food the check had written again, with the check's reason, and each chat's wrong saves.

- A correction **fixed** a value when the value its reason quotes was outside tolerance and the food was saved within it.
- A correction **made a correct food wrong** when the food is a wrong save of that chat and the value its reason quotes was within tolerance.
- For the 27 that **stayed within tolerance**, the food was saved within it. Where the reason quotes the value the check objected to, that value was within tolerance already.

### `gpt-6-luna`: 28 corrections and no gain

- **5 corrections made a correct food wrong.**
  - Cheddar saved as 121 kcal for 30 g, against a reference of 120.9, became 90 kcal in 2 chats. The reason: "For 30 g of cheddar, 121 calories is too high; it is typically about 120 calories per 40 g, or roughly 90 calories per 30 g."
  - The other three raised a fat value that was right: sour cream, feta and the pasta dinner's ground beef.
- **None fixed a value outside tolerance.**
- **6 reasons end by calling the food's values plausible or valid.** The prompt says to reject a value that is implausible, and not one that differs slightly. One reason for a cup of whole milk reads: "the fat value is too low: USDA values are about 7.9 g saturated fat alone and roughly 8 g total fat? Wait, whole milk has about 7.9 g total fat, so this value is plausible."
- **It passed the taco beef** in the 2 chats where the chat model saved it with too much fat.

### `claude-haiku-5-5`: the one model it helped

- **6 corrections fixed a value outside tolerance,** in two foods over 3 chats each:
  - Canned cranberry sauce saved as 52 kcal for 60 g, against 95.4.
  - The taco beef saved as 211 kcal for 120 g, against 276.0.
- **1 made a correct food wrong.** The check questioned the calories of a roasted turkey breast, and the food was written again with 1 g of fat against 3.1.
- **It passed 10 foods with a value outside tolerance.**
  - Swiss cheese was saved at 80 kcal a slice, against 110.0, in 2 chats, and at 49 kcal in a third.
  - The dark chocolate of `seven-items-snacks` was passed in all 3 chats, and its yogurt in 2.
  - The pasta dinner's ground beef was passed in 2 chats.
- **Its wrong saves fell from 19 to 11,** and four more chats were fully correct.

### `deepseek-flash`: worse with the check on

- **4 of its 8 corrections made a correct food wrong.**
  - Two plain rice cakes saved as 70 kcal, against 69.7, were taken for one cake in 2 chats. One was saved with 140 kcal and 29.2 g of carbs, and the other with 30 g of carbs, against 14.7. One reason: "The calories and macros appear to be for a single cake rather than two."
  - Boiled broccoli saved with 7.2 g of carbs, which is the reference value, became 2.9 g.
  - Boiled green beans saved with 10 g of carbs, against 9.9, became 7 g.
- **None fixed a value outside tolerance.**
- **3 reasons end by calling the food's values plausible.**
- **It passed 8 foods with a value outside tolerance,** among them the marinara sauce and the taco beef in all 3 chats each.
- **Its wrong saves rose from 8 to 12,** and three fewer chats were fully correct.

## The date check

The date check ran in every chat of both modes.

| Model | Dates corrected, check off | Check on | Chats that ended with a wrong or missing date |
|---|--:|--:|--:|
| `gpt-6-luna` | 163 | 158 | 0 |
| `claude-haiku-5-5` | 0 | 0 | 0 |
| `deepseek-flash` | 119 | 111 | 0 |

- **It corrected 551 dates,** all on `gpt-6-luna` and `deepseek-flash`. That is more than half of `gpt-6-luna`'s 288 foods in each mode, and about 4 in 10 of `deepseek-flash`'s.
- **What the chat models had written,** going by the check's reasons:
  - `gpt-6-luna` wrote another day in all 321, such as 2025-07-20 for a breakfast eaten on 2026-11-12.
  - `deepseek-flash` wrote another day in 195, and no date in 35.
- **`claude-haiku-5-5` needed no correction.** The check still cost it one request in every `recordFood` call.
- **No chat failed,** so the date check never gave up with `MODEL_ANSWER_UNUSABLE`.

## Cost and time

Each cell is content check off → on.

| Model | Model requests per chat | Cost per chat (USD) | Cost, on ÷ off | Median reply (s) | 95th percentile (s) |
|---|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 4.00 → 9.27 | 0.00081 → 0.00132 | 1.6 | 8.3 → 11.0 | 10.3 → 15.2 |
| `claude-haiku-5-5` | 4.00 → 8.92 | 0.00167 → 0.00334 | 2.0 | 8.5 → 12.2 | 11.5 → 20.6 |
| `deepseek-flash` | 4.00 → 8.93 | 0.00076 → 0.00127 | 1.7 | 5.7 → 6.9 | 7.6 → 9.8 |

- **The content check adds about five requests to a chat,** one for each food and one more for each food it has written again.
- **With the check off, each model averaged 4.00 model requests a chat.** The report gives only the average and does not list a chat's requests, so it does not show how they divide between the chat model, the date check and the date correction.

### The time a message with many foods takes

Issue #113 asks for this before and after. Before is the [many-foods baseline](../many-items/2026-11-16-many-items.md), on the same 20 cases.

| Model | Baseline, no verification: median (s) | 95th percentile (s) | Baseline, old verifier on: median (s) | 95th percentile (s) | This run, check off: median (s) | 95th percentile (s) | This run, check on: median (s) | 95th percentile (s) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 7.7 | 11.9 | 16.4 | 25.5 | 8.3 | 10.3 | 11.0 | 15.2 |
| `claude-haiku-5-5` | 7.9 | 11.4 | 29.3 | 41.6 | 8.5 | 11.5 | 12.2 | 20.6 |
| `deepseek-flash` | 5.5 | 6.9 | 15.7 | 25.2 | 5.7 | 7.6 | 6.9 | 9.8 |

- **With the content check off,** the median chat takes 0.2 to 0.6 seconds longer than with no verification, and about half as long as with the old verifier, or less.
- **With the content check on,** the median chat still takes less than with the old verifier: 11.0, 12.2 and 6.9 seconds against 16.4, 29.3 and 15.7.

### What a chat costs beside the baseline

| Model | Baseline, no verification: requests | Cost (USD) | Baseline, old verifier on: requests | Cost (USD) | This run, check off: requests | Cost (USD) |
|---|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 2.98 | 0.00072 | 8.25 | 0.00122 | 4.00 | 0.00081 |
| `claude-haiku-5-5` | 3.00 | 0.00164 | 8.47 | 0.00397 | 4.00 | 0.00167 |
| `deepseek-flash` | 2.75 | 0.00069 | 10.58 | 0.00159 | 4.00 | 0.00076 |

With the check off, a chat costs about 12%, 2% and 10% more than with no verification, for about one more model request. `recordFood`'s description and the prompts changed between the two runs, so this is a close comparison and not an exact one.

**The run cost about $0.55** by the report's estimates. That is cost per chat times 60 chats for each model and mode:

- $0.13 for `gpt-6-luna`.
- $0.30 for `claude-haiku-5-5`.
- $0.12 for `deepseek-flash`.

The providers' bills were not checked against these. The run was on a Saturday, outside DeepSeek's peak hours, so the report's off-peak price applies.

## Against what counts as done

The [plan](../../records/VERIFICATION_OVERHAUL.md#what-counts-as-done) lists what counts as done for #113. This run speaks to three of its items.

| Item | Result | Evidence |
|---|---|---|
| The low-cost models improve: with the content check on, each beats its baseline result with no verification | Met | 88.3, 85.0 and 81.7 against 80.0, 80.0 and 64.4. |
| No wrong or missing date, and no missing food | Met | None in 360 chats, in either mode. |
| Better with the content check on than off, for each model | Not met | Level on `gpt-6-luna`, four chats higher on `claude-haiku-5-5`, and three chats lower on `deepseek-flash`. |

- **The first item is read with the check on, as the plan says.** The app now runs with it off. Read that way, `gpt-6-luna` and `deepseek-flash` beat their baseline, and `claude-haiku-5-5` is one chat below its own.
- **"No missing food" has no check behind it.** The name and count check is off, so this rests on the chat model and on `recordFood` taking the message's foods as a list.

The plan's other two items are about the app: every check implemented, and every #113 criterion met.

## Decision

The maintainer made one decision from this run, on 2026-11-21. The plan records it as D29, and how it is carried out as D30. The reason is the maintainer's, and the evidence is what this run shows for it.

### The content check is forced off, and kept in the code

**The decision.** No chat of the app runs the content check or its food correction. The check, the correction and their tests stay in the code, as the name and count check does.

**The reason.** Even on the low-cost models, the nutrition values the chat model estimates from the first prompt are good enough. A second model request to check them is not worth what it costs.

**The evidence.**

- **The first estimate is close.** With the check off, 830 of the 864 foods had every nutrition value within tolerance, and the median calorie error was 0.4% or less on each model.
- **The check does not improve every model.** Fully correct chats did not change on `gpt-6-luna`, rose by 4 of 60 on `claude-haiku-5-5`, and fell by 3 of 60 on `deepseek-flash`.
- **It breaks more than it fixes.** 10 correct foods were made wrong, against 6 fixed. It also passed 20 foods with a value outside tolerance.
- **Its cost is certain.** A chat made more than twice the model requests, cost 1.6 to 2.0 times as much, and took 1.2 to 3.7 seconds longer at the median.
- **The run of 2026-11-18 says the same over all 67 cases:** 94.5% and 94.5% fully correct with the check off and on for `gpt-6-luna`, 91.5% and 93.5% for `claude-haiku-5-5`, and 95.5% and 97.0% for `deepseek-flash`.

**How it is forced off.** #113 first asked for a switch in Settings. It was built, and then removed with its endpoint and its file (D30). Java sends `checkNutrition: false` in the `run` payload of every chat. The protocol field and the Python check stay, so the eval harness can still run a case with the check on.

**What a chat loses.** Nothing after the chat model changes a nutrition value. `claude-haiku-5-5` is the model the check helped: it fixed 6 of that model's foods and made 1 wrong. With the check off, that model is at 78.3% on these cases, against 80.0% in the baseline with no verification and 85.0% in the units run with none.

## Other findings

- **Every model is lower than in the run of 2026-11-18,** in both modes. On these 20 cases that run had 55 and 57 of 60 chats fully correct on `gpt-6-luna`, 49 and 54 of 60 on `claude-haiku-5-5`, and 56 and 55 of 59 on `deepseek-flash`. This run has 53 and 53, 47 and 51, and 52 and 49 of 60.
  - **The cause is not known.** Between the two runs the name and count check was switched off, the review changes of [PR #117](https://github.com/phanmi/tyler_agent/pull/117) were made, and `recordFood` started saving its list in one operation. Two of the review changes alter what a correction request sends.
  - **The report gives no sign that the name and count check is the cause.** Every wrong save in this run is a nutrition value, which that check does not change. In the earlier run it changed a record in 4 of 1,206 chats.
  - **It may be chance.** Each figure is one run of about 60 chats. All six figures moved the same way.
- **The taco beef is wrong on every model.** 120 g of cooked 90% lean ground beef was saved with a value outside tolerance in 7 of 9 chats with the check off, and in 5 of 9 with it on.
- **No food was saved with 0 for every nutrition value.** The run of 2026-11-18 had 10 such foods on `claude-haiku-5-5`.
- **No chat called a file tool.** The earlier run had two failed chats where `deepseek-flash` did.

## Limits of this run

- **One run.** On 60 chats, one chat is worth 1.7 points, so a change of a few chats is within noise.
- **One category.** The other 47 cases have not been run with the name and count check off. The maintainer decided on 2026-11-22 that the unit categories' result in the [run of 2026-11-18](2026-11-18-verification-overhaul.md#unit-cases) stands for #113, and that no new run is made for them.
- **What a correction changed is read from its reason.** The report shows the saved values and the check's reason, and not the values the chat model first wrote. So the counts under [What the content check did](#what-the-content-check-did) rest on the values the reasons quote.
- **Java's rules are not applied.** A stand-in answers the Java operations. The time measured is the Python agent's, without the app around it.
- **Causes are not shown.** The report keeps each check's reason, but no transcript of the chat model's own requests.
- **Replies are not kept.** The report shows what was saved, not what the agent told the user.

## Appendix

The [generated report](2026-11-21-content-check-report.md) has every measure for each model. It lists every chat that was not fully correct, with the saved and expected values that differ, and every change a check made, with its reason.
