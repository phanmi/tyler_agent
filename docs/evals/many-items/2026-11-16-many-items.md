# Many-foods eval baseline, November 2026

[Eval harness](../../../agent-python/evals/README.md) · [Full generated report](2026-11-16-many-items-report.md) · [First eval report](../eval-harness/2026-11-12-food-recording.md)

**This run is the baseline for messages that list several foods.** It covers a run of the 20 `many-items` cases ([#111](https://github.com/phanmi/tyler_agent/issues/111)) against the three low-cost models. A later run of the same cases is compared with the numbers here.

It also sets a goal for food verification, which lowered every model's result in this run.

## Summary

- **Baseline with verification off.** 80.0% of chats were fully correct on `gpt-6-luna` and on `claude-haiku-5-5`, and 64.4% on `deepseek-flash`.
- **Verification made every model worse.** With it on, those numbers fell to 58.3%, 73.3% and 47.5%.
- **It dropped foods the user listed.** With verification on, `claude-haiku-5-5` missed 5 foods and `deepseek-flash` missed 28, against none with it off. Every food in these cases is named with an amount.
- **It accepted records that hold the whole meal.** A record named for one food carried a unit such as "meal" and the calories of the whole message. That happened in 12, 3 and 6 chats with verification on, against 3, 0 and 0 with it off.
- **It fixed DeepSeek's dates.** `deepseek-flash` saved a wrong or missing date in 17 chats with verification off and in none with it on. This is the only thing verification improved.
- **It cost 1.7 to 2.4 times as much for each chat and took two to four times as long.** The median reply on `claude-haiku-5-5` went from 7.9 to 29.3 seconds.
- **Goal.** Verification needs a lot of work, and right now it makes the result worse. The verification tool needs an overhaul, tracked in [#113](https://github.com/phanmi/tyler_agent/issues/113). See [the goal](#goal-overhaul-the-verification-tool).

## What was run

| | |
|---|---|
| Date | 2026-11-16, from 00:37 to about 02:07 UTC |
| Code | Food verification from [#96](https://github.com/phanmi/tyler_agent/issues/96), the eval harness from [PR #102](https://github.com/phanmi/tyler_agent/pull/102) and the model list from [PR #110](https://github.com/phanmi/tyler_agent/pull/110). The generated report shows version 0.2.1, the release version in the build configuration; these changes are newer than that release. |
| Cases | The 20 `many-items` cases, with 96 expected records and reference nutrition from USDA FoodData Central. After the run, `spaghetti sauce` was added to the accepted names of one sauce record. It changes only which record a sauce saved under that name is compared with. |
| Repeats | Each case 3 times with food verification off and 3 times with it on |
| Models | `gpt-6-luna`, `claude-haiku-5-5` and `deepseek-flash` (DeepSeek V4.1 Flash): each provider's low-cost model, which `--cheap-models` selects |
| Chats | 360, or 120 for each model. One chat failed; see [Other findings](#other-findings). |

Each case is one message about today that lists 3 to 8 foods, each with an amount. For example: "Lunch: 150 g of grilled chicken breast, 1 cup of cooked brown rice, 1 cup of chopped boiled broccoli and 1 tablespoon of olive oil."

| Foods in the message | 3 | 4 | 5 | 6 | 7 | 8 |
|---|--:|--:|--:|--:|--:|--:|
| Cases | 4 | 6 | 3 | 5 | 1 | 1 |

A chat is **fully correct** when every food the case expects is saved with the right name, amount, unit, date and nutrition, and nothing else is saved. Calories pass within 20% or 15 kcal of the reference, whichever is wider, and protein, carbs, fat and fiber within 20% or 2 g. The [harness README](../../../agent-python/evals/README.md#read-the-report) defines every measure.

## Results

Each model is measured over the chats that finished in both modes, shown as **Compared**. Wrong saves and missed items are counts of food records over those chats.

### Verification off

| Model | Compared | Fully correct (%) | Wrong saves | Missed items | Cost per chat (USD) | Median reply (s) | 95th percentile (s) |
|---|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 60 | 80.0 | 12 | 15 | 0.00072 | 7.7 | 11.9 |
| `claude-haiku-5-5` | 60 | 80.0 | 14 | 0 | 0.00164 | 7.9 | 11.4 |
| `deepseek-flash` | 59 | 64.4 | 65 | 0 | 0.00069 | 5.5 | 6.9 |

### Verification on

| Model | Compared | Fully correct (%) | Wrong saves | Missed items | Refused | Saved after a rejection | Cost per chat (USD) | Median reply (s) | 95th percentile (s) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 60 | 58.3 | 22 | 18 | 7 | 13 | 0.00122 | 16.4 | 25.5 |
| `claude-haiku-5-5` | 60 | 73.3 | 13 | 5 | 5 | 15 | 0.00397 | 29.3 | 41.6 |
| `deepseek-flash` | 59 | 47.5 | 13 | 28 | 31 | 62 | 0.00159 | 15.7 | 25.2 |

**Refused** counts records verification rejected twice and did not save. **Saved after a rejection** counts records it rejected once and then accepted.

### Fully correct chats by number of foods

Each cell is fully correct chats out of chats compared, with verification off and then on.

| Foods | `gpt-6-luna` off | on | `claude-haiku-5-5` off | on | `deepseek-flash` off | on |
|---|--:|--:|--:|--:|--:|--:|
| 3 | 9/12 | 5/12 | 12/12 | 10/12 | 5/12 | 3/12 |
| 4 | 18/18 | 12/18 | 17/18 | 12/18 | 11/18 | 7/18 |
| 5 | 8/9 | 7/9 | 6/9 | 7/9 | 6/9 | 7/9 |
| 6 | 8/15 | 9/15 | 10/15 | 12/15 | 10/14 | 6/14 |
| 7 | 2/3 | 0/3 | 0/3 | 0/3 | 3/3 | 2/3 |
| 8 | 3/3 | 2/3 | 3/3 | 3/3 | 3/3 | 3/3 |

A longer message was not clearly harder than a short one. The 7-food and 8-food rows are one case each, so they say little.

## What verification changed

The table counts chats. A chat with two kinds of problem is in two rows.

| What went wrong in the chat | `gpt-6-luna` off | on | `claude-haiku-5-5` off | on | `deepseek-flash` off | on |
|---|--:|--:|--:|--:|--:|--:|
| A record holds the whole meal | 3 | 12 | 0 | 3 | 0 | 6 |
| A food is missing | 3 | 10 | 0 | 4 | 0 | 24 |
| A date is wrong or missing | 0 | 0 | 0 | 0 | 17 | 0 |
| An amount or unit differs from the user's | 2 | 2 | 2 | 1 | 0 | 1 |
| A nutrition value is outside the tolerance | 7 | 7 | 10 | 9 | 5 | 3 |
| A food was saved that the case does not expect | 0 | 0 | 0 | 0 | 0 | 3 |
| **Chats not fully correct** | **12** | **25** | **12** | **16** | **21** | **31** |

A record is counted as holding the whole meal when its unit is wrong and its calories are at least 1.8 times the reference for the food it is named for. Its other wrong fields are not counted again in the rows below it.

### It dropped foods the user listed

A record that verification rejects twice is refused: it is not saved, and the chat model is told to tell the user and not to try again.

- **How many records were refused.** 7 on `gpt-6-luna`, 5 on `claude-haiku-5-5` and 31 on `deepseek-flash`.
- **What was lost.** With verification off, `claude-haiku-5-5` and `deepseek-flash` missed no food at all. With it on they missed 5 and 28. Examples are "1 cup of blueberries" in all three `claude-haiku-5-5` chats of one case, and "1 medium apple", "28 g of walnuts" and an English muffin on `deepseek-flash`.
- **`gpt-6-luna` is a mixed case.** It missed 15 foods with verification off, all in three chats that saved one record for the whole meal. With it on it missed 18: 12 in chats with a whole-meal record, and 6 single foods in other chats.
- **A food was swapped for another.** In 3 `deepseek-flash` chats with verification on, one food was missing and another food from the same message was saved twice. In one, the pumpkin pie was missing and the turkey had two records.
- **Why it matters.** Every food in these cases is named in the message with an amount, so there was nothing for verification to doubt about whether the user ate it.
- **What is not known.** The report does not say which missed food was refused, and it keeps no reason for a rejection.

### It accepted records that hold the whole meal

| Model | Case | Record saved | Reference for that food | The whole message |
|---|---|---|---|--:|
| `gpt-6-luna` | `four-items-salmon-dinner` | salmon: 1 "meal", 676 kcal | 6 oz, 350.4 kcal | 676.0 kcal |
| `claude-haiku-5-5` | `four-items-chicken-lunch` | chicken breast: 1 "meal", 638 kcal | 150 g, 226.5 kcal | 648.9 kcal |
| `deepseek-flash` | `three-items-eggs-breakfast` | egg: 1 "meal", 526 kcal | 2 eggs, 180.3 kcal | 453.2 kcal |

- **The verifier accepted each one.** With verification on, a record is saved only after the verifier accepts it. Its instructions say to reject a record whose amount or unit does not match what the user said.
- **The other foods were often saved as well.** In 8 of the 12 `gpt-6-luna` chats, 2 of the 3 `claude-haiku-5-5` chats and 3 of the 6 `deepseek-flash` chats, every other food in the message also had a record of its own. The diary then counts those foods twice. In the salmon dinner above it shows about 1,000 kcal for a 676 kcal meal.
- **Verification is what added them** on two of the three models. `claude-haiku-5-5` and `deepseek-flash` saved no such record with verification off. `gpt-6-luna` saved one in 3 chats with verification off, each time for a six-food message, and in 12 with it on.
- **What is not known.** Either verification's correction step wrote these records, or the chat model sent them after a refusal. The run keeps neither the rejected records nor the reasons, so it cannot say which.

### It fixed DeepSeek's dates

With verification off, `deepseek-flash` saved 61 records with a wrong or missing date, in 17 of its 59 chats: 49 records dated 2026-09-07 and 12 with no date. It made 2.75 model calls for each chat, where the other two models made 3. That fits it skipping the lookup of today's date in about a quarter of its chats.

With verification on, no record had a wrong date, and 62 records were saved after a rejection. That fits verification rejecting the date and saving a corrected record. The [first eval report](../eval-harness/2026-11-12-food-recording.md#other-findings) found the same, and noted that giving the agent today's date in every conversation would likely fix it without verification.

### It cost more and took longer

Each `recordFood` call makes one to three more model requests, one food after another.

| Model | Model calls per chat, off → on | Cost per chat, on ÷ off | Median reply (s), off → on | 95th percentile (s), off → on |
|---|--:|--:|--:|--:|
| `gpt-6-luna` | 2.98 → 8.25 | 1.7 | 7.7 → 16.4 | 11.9 → 25.5 |
| `claude-haiku-5-5` | 3.00 → 8.47 | 2.4 | 7.9 → 29.3 | 11.4 → 41.6 |
| `deepseek-flash` | 2.75 → 10.58 | 2.3 | 5.5 → 15.7 | 6.9 → 25.2 |

## Goal: overhaul the verification tool

Tracked in [#113](https://github.com/phanmi/tyler_agent/issues/113).

**Where it stands.** Verification needs a lot of work. In this run it actively made the result worse: every low-cost model had fewer fully correct chats with it on, it dropped foods the user listed, and it let through records that count a meal twice. When this run was made, verification was on for every chat in the app.

**Target.** The verification tool needs an overhaul.

**What the overhaul has to answer.** None of this is decided. The design for the overhaul settles each point.

1. **Show why a record was rejected.** The eval report counts rejections and keeps no reason. Until it lists each rejected record with the verifier's reason, the causes below are guesses.
2. **Check one food against a message that lists many.** The verifier sees the whole conversation and one record. Find out whether it rejects a record for leaving out the message's other foods.
3. **Never accept or write a record for more than one food.** The verifier should reject a unit such as "meal" with the message's total calories, and the correction step should not be able to widen a record to the whole message.
4. **Decide what a refusal does.** Two rejections drop a food the user plainly listed, and the chat model is told not to try again.
5. **Cut the cost and the wait.** The checks run one food after another. [#101](https://github.com/phanmi/tyler_agent/issues/101) lists options, including running them at the same time.
6. **Give the agent today's date in every conversation.** Dates are the one thing verification fixed here, and this would fix them without it.

**Related issues.**

- [#98](https://github.com/phanmi/tyler_agent/issues/98) adds the Settings switch. Its default is to be based on eval results, and this run bears on it.
- [#100](https://github.com/phanmi/tyler_agent/issues/100) lets verification see tool results.
- [#101](https://github.com/phanmi/tyler_agent/issues/101) covers partial saves and the time a message with several foods takes.

## Other findings

These are about the baseline with verification off, apart from the last. They are not yet planned.

1. **`deepseek-flash` is held back by dates.** 16 of its 21 chats that were not fully correct had a wrong or missing date and no other problem.
2. **`gpt-6-luna` saved a six-food message as one record in 3 chats.** Each saved one record with the meal's total and missed the other five foods, which is all 15 of its missed items with verification off. One of those records was also dated 2025-04-08.
3. **A few records account for most nutrition misses.** The other two models save each of these within the tolerance.
   - `claude-haiku-5-5`: a slice of Swiss cheese at 80 kcal against 110.0 (all 6 chats), 170 g of plain low-fat yogurt with its protein or carbs outside the tolerance (5 of 6), and 60 g of cranberry sauce at 125 or 185 kcal against 95.4 (4 of 6).
   - `gpt-6-luna`: whole wheat bread at 100 kcal a slice against 80.6 (7 chats over three cases).
   - `deepseek-flash`: 120 g of 90% lean ground beef at 26 g of protein against 34.1 (4 of 6).
4. **Units were changed in a few chats.** `claude-haiku-5-5` saved a medium potato as 173 g, and a tablespoon of butter and a cup of orange juice as 14 g and 248 ml. `gpt-6-luna` saved a tomato's unit as "medium (about 123 g)". Keeping the user's unit is [#104](https://github.com/phanmi/tyler_agent/issues/104).
5. **The five-round limit was not what missed foods.** With verification off a chat made about three model calls: one to look up the date, one that saved every food, and one to reply.
6. **One chat failed, on the 300-second limit.** It was a `deepseek-flash` chat for a six-food breakfast with verification on. Three of the six foods had been saved when it stopped, which is the partial save [#101](https://github.com/phanmi/tyler_agent/issues/101) describes.

## Cost

The report's estimate for the run is about $0.59: $0.12 for `gpt-6-luna`, $0.34 for `claude-haiku-5-5` and $0.13 for `deepseek-flash`. The providers' bills were not checked against it.

The `deepseek-flash` chats ran last, most likely after 01:00 UTC on a Monday, when DeepSeek charges its peak rate. The report prices DeepSeek at the off-peak rate, so its real cost may be up to twice the estimate.

## Limits of this run

- **The sample is small.** Each model ran 60 chats in each mode, so one chat is worth 1.7 points. The 4-chat drop on `claude-haiku-5-5` is within noise on its own. The drops on `gpt-6-luna` (13 chats) and `deepseek-flash` (10 chats) are larger. The refused records and the whole-meal records are counted directly and do not depend on that comparison.
- **Rejection reasons are not kept.** The report shows what was saved and how many records were rejected, not why.
- **Only the low-cost models ran.** `claude-sonnet-5-5` and `gpt-6-sol` were run on an earlier version of these cases, and verification did not lower their results there: 71.7% and 66.7% fully correct with it off, 73.3% and 70.0% with it on. So the problems here may be specific to the low-cost models.
- **Earlier runs are not comparable.** Two runs before this one used a version of the cases with twelve more foods and five cases for named dishes. Those were removed because one reference value could not be fair for them; the [harness README](../../../agent-python/evals/README.md#add-a-case) gives the rule. This baseline is the first run of the 20 cases as they are.
- **Sizes 7 and 8 have one case each**, so 3 chats in each mode for each model.
- **The messages are simple.** Every food has an amount and every message is about today. Foods without an amount and relative dates are covered by other categories.
- **Replies are not kept.** The report shows what was saved, not what the agent told the user about a refused food.

## Appendix

The [full generated report](2026-11-16-many-items-report.md) has every measure for each model and lists every chat that failed or was not fully correct, with the saved and expected values that differ.
