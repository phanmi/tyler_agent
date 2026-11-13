# Food recording eval, November 2026

[Eval harness](../../agent-python/evals/README.md) · [Full generated report](2026-11-12-food-recording-report.md)

This report covers the first full run of the food recording eval ([#97](https://github.com/phanmi/tyler_agent/issues/97)) against the eight models Tyler suggests in Settings. It records the results, the model adjustment they led to, and the improvements planned from what the run found.

## Summary

- **The OpenAI and Claude models record food about equally well.** With food verification off, 91.5% to 95.5% of their chats were fully correct. Differences of a few points are within this run's noise.
- **DeepSeek models often save invented dates instead of looking up today's date.** Verification catches most of these, and that is nearly all of its benefit for DeepSeek.
- **Verification did not improve the OpenAI or Claude results.** It added 22% to 57% to their cost per chat and 1.6 to 3.1 seconds to their median reply.
- **Model adjustment ([#103](https://github.com/phanmi/tyler_agent/issues/103)).** `deepseek-v4-pro` and `gpt-5.6` will be removed from the suggested models, and `gpt-6-luna` added. `deepseek-v4-pro` was worse than `deepseek-flash` on every measure apart from two ties, at three to four and a half times the cost per chat. `gpt-5.6` was no more accurate than the other OpenAI models at about twice the cost.
- **Three improvements are planned:** keep the unit the user gives ([#104](https://github.com/phanmi/tyler_agent/issues/104)), ask the user for missing details before saving ([#106](https://github.com/phanmi/tyler_agent/issues/106)), and prefer USDA FoodData Central for nutrition values ([#105](https://github.com/phanmi/tyler_agent/issues/105)).
- **The run cost $19.81** across the three providers.

## What was run

| | |
|---|---|
| Date | 2026-11-12, from 02:38 to about 05:42 UTC |
| Code | Food verification from [#96](https://github.com/phanmi/tyler_agent/issues/96) and the eval harness from [PR #102](https://github.com/phanmi/tyler_agent/pull/102). The generated report shows version 0.2.1, the release version in the build configuration; both changes are newer than that release. |
| Cases | 39 labelled cases in 9 categories, with reference nutrition from USDA FoodData Central |
| Repeats | Each case 3 times with food verification off and 3 times with it on |
| Models | `gpt-6-sol`, `gpt-6.1-sol`, `gpt-5.6`, `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-haiku-5-5`, `deepseek-flash` (DeepSeek V4.1 Flash), `deepseek-v4-pro` (DeepSeek V4 Pro) |
| Chats | 1,872, or 234 per model; every planned chat ran |

A chat is **fully correct** when every food the case expects is saved with the right name, amount, unit, date and nutrition, and nothing else is saved. Calories pass within 20% or 15 kcal of the reference, whichever is wider, and protein, carbs, fat and fiber within 20% or 2 g. The [harness README](../../agent-python/evals/README.md#read-the-report) defines every measure.

## Results

Each model is measured over the chats that finished in both modes, shown as **Compared**. Wrong saves and missed items are counts of food records over those chats.

### Verification off

| Model | Compared | Fully correct (%) | Wrong saves | Missed items | Cost per chat (USD) | Median reply (s) | 95th percentile (s) |
|---|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-sol` | 117 | 91.5 | 7 | 3 | 0.00673 | 4.2 | 5.4 |
| `gpt-6.1-sol` | 117 | 92.3 | 6 | 3 | 0.00559 | 5.5 | 6.9 |
| `gpt-5.6` | 117 | 92.3 | 9 | 3 | not priced | 4.4 | 6.0 |
| `claude-sonnet-5-5` | 111 | 93.7 | 7 | 0 | 0.01554 | 4.6 | 7.0 |
| `claude-opus-5-5` | 111 | 95.5 | 5 | 0 | 0.03278 | 5.9 | 8.7 |
| `claude-haiku-5-5` | 117 | 94.0 | 7 | 0 | 0.00082 | 3.5 | 5.2 |
| `deepseek-flash` | 114 | 73.7 | 34 | 1 | 0.00051 | 3.7 | 4.8 |
| `deepseek-v4-pro` | 101 | 29.7 | 80 | 4 | 0.00147 | 3.8 | 5.4 |

### Verification on

| Model | Compared | Fully correct (%) | Wrong saves | Missed items | Refused | Cost per chat (USD) | Median reply (s) | 95th percentile (s) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-sol` | 117 | 92.3 | 6 | 3 | 0 | 0.00835 | 5.8 | 9.2 |
| `gpt-6.1-sol` | 117 | 92.3 | 6 | 3 | 0 | 0.00684 | 7.7 | 10.8 |
| `gpt-5.6` | 117 | 88.9 | 10 | 6 | 0 | not priced | 6.0 | 8.9 |
| `claude-sonnet-5-5` | 111 | 90.1 | 11 | 0 | 0 | 0.02110 | 7.6 | 17.0 |
| `claude-opus-5-5` | 111 | 92.8 | 8 | 0 | 0 | 0.04224 | 9.0 | 15.3 |
| `claude-haiku-5-5` | 117 | 89.7 | 9 | 5 | 2 | 0.00129 | 6.1 | 19.1 |
| `deepseek-flash` | 114 | 86.8 | 9 | 8 | 6 | 0.00086 | 5.0 | 11.5 |
| `deepseek-v4-pro` | 101 | 81.2 | 8 | 16 | 38 | 0.00390 | 8.8 | 25.4 |

**Refused** counts foods verification rejected twice, so nothing was saved. `gpt-5.6` has no cost per chat because its only listed price on 2026-11-11 was a promotion, which the price table leaves out.

## Cost

| Provider | Billed | Report's estimate | Notes |
|---|--:|--:|---|
| OpenAI | $6.63 | $3.22 | The estimate covers `gpt-6-sol` and `gpt-6.1-sol` only. Pricing `gpt-5.6`'s tokens at its listed $4 input and $20 output per million adds $3.40, for $6.62 in all. |
| Anthropic | $12.81 | $12.64 | The estimate leaves out the 24 Sonnet and Opus chats that failed, which were still billed for the calls they made. |
| DeepSeek | $0.37 | $0.70 | The price table uses DeepSeek's peak rate. The DeepSeek chats ran last, most likely in DeepSeek's off-peak hours, which are half price. |
| **Total** | **$19.81** | | |

Where the price table has a model's actual rate, the report's token-based cost is within 2% of the bill.

## Decision: model adjustment

Settings will stop suggesting `deepseek-v4-pro` and `gpt-5.6`, and will suggest `gpt-6-luna`. This is tracked in [#103](https://github.com/phanmi/tyler_agent/issues/103).

### Remove `deepseek-v4-pro`

`deepseek-v4-pro` (DeepSeek-V4-Pro-0813) was worse than `deepseek-flash` on every measure in this run apart from two ties, and it cost three to four and a half times as much per chat. There is no task in this eval where it is the better DeepSeek choice.

| Measure | `deepseek-flash` off | `deepseek-v4-pro` off | `deepseek-flash` on | `deepseek-v4-pro` on |
|---|--:|--:|--:|--:|
| Fully correct (%) | 73.7 | 29.7 | 86.8 | 81.2 |
| Wrong saves per chat | 0.30 | 0.79 | 0.08 | 0.08 |
| Missed items per chat | 0.01 | 0.04 | 0.07 | 0.16 |
| Foods refused by verification | – | – | 6 | 38 |
| Chats with a wrong or missing date | 19 | 73 | 2 | 1 |
| Median calorie error (%) | 0.3 | 0.4 | 0.3 | 0.4 |
| Cost per chat (USD) | 0.00051 | 0.00147 | 0.00086 | 0.00390 |
| Median reply (s) | 3.7 | 3.8 | 5.0 | 8.8 |
| 95th percentile reply (s) | 4.8 | 5.4 | 11.5 | 25.4 |

- **The two ties** are wrong saves per chat with verification on, and the median reply with verification off. `deepseek-v4-pro` reached the first tie by refusing 38 foods, against 6 for `deepseek-flash`, so it missed twice as many.
- **It made fewer model calls with verification off** (2.05 per chat against 2.63) mainly because it skipped looking up the time, which is also why it saved a wrong date in 73 chats.
- **Failed chats are not counted against it.** All of its failed chats came from a defect in the harness, described under [Other findings](#other-findings).

### Remove `gpt-5.6`

`gpt-5.6` was no more accurate than the other two OpenAI models and cost about twice as much per chat.

- **Accuracy:** it matched `gpt-6.1-sol` with verification off (92.3% fully correct) and dropped to 88.9% with it on.
- **Errors:** it had the most wrong saves of the three (9 off and 10 on, against 6 or 7), and saved "2 slices of bread with 2 tablespoons of peanut butter" as one combined record in all 6 chats.
- **Cost:** at its listed price of $4 input and $20 output per million tokens, a chat cost about $0.013 off and $0.016 on, against $0.0067 and $0.0084 for `gpt-6-sol`. That price is a promotion that ends on 2026-11-30, and OpenAI publishes no price for after it.

### Add `gpt-6-luna`

OpenAI describes `gpt-6-luna` as its "most efficient model for focused, high-volume tasks", which describes recording food. It supports the Responses API and function calling, and is listed at one twentieth of `gpt-6-sol`'s rates: $0.10 input and $0.50 output per million tokens. It was not part of this run, so it will be run through the eval when it is added.

### What changes

- **Suggestions:** OpenAI becomes `gpt-6-sol`, `gpt-6.1-sol` and `gpt-6-luna`; DeepSeek becomes `deepseek-flash` only. Claude and every default are unchanged.
- **Saved choices:** Settings accepts any model ID, so a user who saved a removed model keeps it as a custom model ID.
- **Naming:** the docs will say that `deepseek-flash` is DeepSeek V4.1 Flash, as DeepSeek's documentation states (DeepSeek-V4.1-Flash-1017).

## Planned improvements

### 1. Keep the unit the user gives

Tracked in [#104](https://github.com/phanmi/tyler_agent/issues/104).

**Goal.** The agent records the amount in the unit the user used, even an uncommon one such as a cup, a slice, a medium order or a scoop. It does not convert the amount to grams or millilitres, and it does not add a conversion to the unit text.

**Evidence.** In 25 chats the agent changed a unit the user gave, nearly all of them on Claude models. Verification was on in 14 of them and did not catch the change.

| Model | The user said | Saved | Chats |
|---|---|---|--:|
| `claude-sonnet-5-5` | one 2 oz Snickers bar | 57 g | 6 |
| `claude-sonnet-5-5` | 1 cup of milk or orange juice | 240 or 244 ml, or `cup (240 ml)` | 9 |
| `claude-sonnet-5-5` | 6 oz of chicken breast | 170 g | 1 |
| `claude-opus-5-5` | 6 oz of chicken breast | 170 g | 3 |
| `claude-haiku-5-5` | a medium McDonald's fries | 111 or 117 g | 4 |
| `claude-haiku-5-5` | half a cup of broccoli | 78 g | 1 |
| `deepseek-v4-pro` | a medium McDonald's fries | 117 g | 1 |

**Measurement.** Add cases with uncommon units, such as a scoop of protein powder or a can of soup, and score the unit against the user's own word. The Snickers case should accept both "1 bar" and "2 oz", because the user gave both.

### 2. Ask the user for missing details before saving

Tracked in [#106](https://github.com/phanmi/tyler_agent/issues/106). A tool that turns portion descriptions into weights is tracked in [#107](https://github.com/phanmi/tyler_agent/issues/107).

**Goal.** The agent asks the user before saving when something that changes the record is missing, instead of guessing. This is how the agent always behaves; there is no setting.

- **A vague or variable amount.** For "I had some almonds" or "a bowl of rice", the agent asks for an estimate and suggests how to give one: compare with part of the hand or a common object. The user can then answer, for example, "a cupped handful" or "a bowl about the size of my fist". A count of a food with a usual size, such as "a banana" or "two eggs", is saved without asking.
- **Details that change the nutrition a lot.** For a pizza, the agent asks which toppings and crust it had. It also asks whether a food was fried or grilled, about dressings and sauces, and about a drink's size or sugar. It does not ask about details with little effect.
- **One round of questions.** One reply covers every food in the message that needs a question. Foods that need none are saved straight away.

The agent saves the food once the user answers, with the answer as the amount and unit in the user's words. When the user does not know, it saves a typical portion and says it is an estimate that can be edited in the calendar. For now that typical portion is the model's own estimate; the portion estimation tool will supply it.

**Evidence.**

- **Guessed portions vary.** `gpt-5.6` once recorded "a bowl of white rice" at 310 kcal, against 205 kcal for the reference cup. The cases with no amount accept a wider range than the others, up to 50%, so the eval hides most guesses like this.
- **Some models already hold back.** The OpenAI models left "some almonds" unrecorded in 12 of 18 chats, and `deepseek-v4-pro` in all 5 that finished. The report does not keep replies, so it cannot show whether they asked for the amount, but that is the likely reason. This behaviour should be deliberate and consistent across models.

**Measurement.** The harness scores only saved records. It needs two-turn cases in which the first turn saves nothing for the food and a scripted answer from the user follows. The "some almonds" and "bowl of rice" cases change from accepting an estimate to expecting a question; "a banana" and "a baked potato" stay direct records.

### 3. Prefer USDA FoodData Central for nutrition values

Tracked in [#105](https://github.com/phanmi/tyler_agent/issues/105).

**Goal.** The agent's instructions say to base the nutrition values it estimates on USDA FoodData Central. Values the user states still win and are saved exactly as stated.

**Evidence.** The eval's reference values are FoodData Central records, and most estimates were already close: the median calorie error was 0.3% to 0.4% for every model. The largest misses were on branded food. Two slices of Pizza Hut pepperoni pan pizza were saved with 36 to 40 g of fat against the reference 29.6 g, or 32 to 36 g of protein against 26.0 g, in 26 of the 48 chats with this case.

**Limit.** An instruction steers the model toward the FoodData Central values it remembers; it cannot look them up. Grounding values in the database needs a lookup tool for the FoodData Central API, which #96 left out of scope. That is a separate decision.

## Other findings

These are not yet planned. Each needs its own decision.

1. **A harness defect failed 43 chats.** The harness's stand-in for the user profile uses lowercase field names, where Tyler's profile uses `Name`, `Gender`, `Age` and `JobType`. Any chat in which the model read the profile ended with a `KeyError`. This caused every failed chat in the run; there were no provider errors or rate limits. It left the two "should record nothing" questions unmeasured for `claude-sonnet-5-5` and `claude-opus-5-5`. It was fixed in PR #102 after this run, so the next report measures those questions for every model.
2. **DeepSeek models invent dates without verification.** Instead of looking up today's date, they often saved dates such as 2026-09-07 and 2025-11-03. Verification reduced the chats with a wrong or missing date from 19 to 2 for `deepseek-flash` and from 73 to 1 for `deepseek-v4-pro`. Giving the agent today's date in every conversation would likely fix this without verification.
3. **Verification did not pay off for the OpenAI and Claude models.** It added about one model call per chat and did not improve accuracy. Four of the six models scored 2.7 to 4.3 points lower with it on, which is within noise. This bears on the default for the Settings switch in [#98](https://github.com/phanmi/tyler_agent/issues/98).
4. **A value the user stated was not always kept.** With verification on, `claude-haiku-5-5` refused to save a banana the user logged at 1,900 kcal in 2 of 3 chats, and a chicken breast with stated label calories in 1. `deepseek-v4-pro` refused the banana in 1. With verification off, `deepseek-flash` saved the banana at 105 kcal in 2 chats. All of these break the rule that values the user states are saved exactly as stated.
5. **Two foods in one sentence were sometimes saved as one record.** For "2 slices of whole wheat bread with 2 tablespoons of smooth peanut butter", `gpt-5.6` saved one combined serving in all 6 chats. `claude-haiku-5-5`, `claude-sonnet-5-5`, `deepseek-flash` and `deepseek-v4-pro` combined, dropped or duplicated a food in 2 or 3 chats each.
6. **`claude-haiku-5-5` gave the best value in this run.** With verification off, 94.0% of its chats were fully correct, at $0.00082 per chat and the fastest median reply (3.5 seconds). Only `claude-opus-5-5` scored higher, by 1.5 points, at 40 times the cost per chat.

## Limits of this run

- **The sample is small.** With 39 cases and 3 repeats, one chat is worth about 0.9 points of "fully correct", so differences of a few points between models are noise.
- **Two cases are scored more strictly than the rules they test.** The Snickers message gives both "one bar" and "2 oz", but the case accepts only the bar, which accounts for 4 to 6 wrong saves for every model except `claude-sonnet-5-5`. The pizza reference is a 2018 USDA record, which may not match the chain's current recipe.
- **Replies are not kept.** The report shows what was saved, not what the agent said.
- **Only food recording is measured.** Workout plans and general conversation are not covered.
- **DeepSeek is priced at its peak rate**, so the report overstates its cost when chats run off-peak.

## Appendix

The [full generated report](2026-11-12-food-recording-report.md) has every measure for each model and lists every chat that failed or was not fully correct, with the saved and expected values that differ.
