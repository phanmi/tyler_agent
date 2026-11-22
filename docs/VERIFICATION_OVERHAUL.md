# Food verification overhaul

[Issue #113](https://github.com/phanmi/tyler_agent/issues/113) | [Agent operations](AGENT_OPERATIONS.md#food-verification) | [Python agent standards](PYTHON_AGENT_STANDARDS.md) | [Eval harness](../agent-python/evals/README.md)

This tracker covers the food verification overhaul in issue #113. The requirements and acceptance criteria are in #113, including those folded in from #98, #100 and #101. This document records the design, the decisions, the eval runs and the progress, so the work can resume one step at a time.

**Direction:** the current verification tool is split into separate tools. Decided by the maintainer on 2026-11-17. Which tools, and what each one checks, is left to the design.

## The verification tool before the overhaul

This is the tool the overhaul replaces, as it was when the plan was set up. Step 6 removed it, so the names below are no longer in the code.

| Area | How it worked |
|---|---|
| Where it runs | `recordFood`'s adapter called `verified_food` in `verification.py` before it requested `food.insert`. It ran when `RunSettings.verify_food` was on, which was the default; the `run` payload had no field for it, so only code such as the eval runner and tests turned it off. |
| What one check covers | One model request judged the whole record through `submitFoodVerdict {valid, reason}`: the food, the amount and unit, the date, the values the user stated, and whether the values the user did not state are plausible. |
| After a rejection | The model regenerated the record from the verdict's reason, and the new record was checked again. A record rejected `MAX_REJECTIONS` (2) times was refused: it was never sent to Java, and the chat model was told not to call `recordFood` for it again. Each item cost one to three model requests, one after another. |
| What it sees | Today's date from Java, the conversation as text (saved history plus the current message), and the record. It saw no tool results. |
| Which model | The run's own provider and model, through `ToolContext.respond`. |
| How evals count it | `evals/runner.py` and `evals/report.py` counted verification outcomes from the model calls inside each `recordFood` call. |

## Baseline eval runs

The overhaul is compared with these runs.

| Date | Run | Write-up |
|---|---|---|
| 2026-11-16 | `--cheap-models --category many-items`, verification off and on | [Many-items eval](evals/many-items/2026-11-16-many-items.md) |
| 2026-11-17 | #104's units categories, default and low-cost models, verification off and on | [Units eval](evals/units/2026-11-17-units.md) |

## Design

Instead of one big verifictaion tool that ask model to regenerate the whole input when asked. The verification should go like this.
```
chat -> model: model returning an initial json. If the json was not returned, the original record food tool ask model to regenerate.
python -> memory: saves json inside memory in a data class (since there will be case where multiple food needed to be recorded, make it a list of food).
python -> model: trigger verification tool #1, verify date, python asks the model to compare the chat with the date returns true or falce (accept or reject).
model -> python: if it is reject, asks the model to regenerate a date using a correction tool. only changing the date value inside memory.
python -> model: python list out a a set of names that was saved, get back to model with the original chat., (only food name and the chat is passed in, sometimes uses readfile tools to read whatever is inside for example mealplan.md). asks the model if the name and the count of the food is correct. model returns a set of missing food, a set of incorrect food(that would mean food created by hallucination or wrong names)
model -> python: if there is missing food or incorrect food, regenerate the missing food(done by model), remove the incorecct food through a correction tool (done by python).
python -> model: get a list of current food ask model to verify one by one, model only thinks if the content of the nutrition makes sense against USDA or other data.(it doesn't input the chat itself, just whats inside the python memory) if returned true, its good(definitely needs an on or off toggle).
python -> model: regenerate the one that is false.
```

### The split

split the big tool into:
```
verify date tool
correct date tool
verify food name and count tool
verify food content tool
correct food tool
```
### What counts as done

Stated by the maintainer on 2026-11-17:

1. Every check tool in the split is implemented, and the content check can be turned off from the Settings page.
2. Every #113 acceptance criterion is met.
3. An eval run shows that the cheap models improve.
4. The name and count check and the date check always run, so the eval shows no wrong or missing date and no missing food.
5. With the content check on, the results are better than with it off.

The eval results are read against items 3 to 5 as follows (agreed on 2026-11-17):

- **Item 3.** For each cheap model, "Fully correct chats (%)" with the switch on is higher than that model's verification-off result in the baseline run of the same cases. The baseline's off results beat its on results for every model, so beating "off" shows the new checks do better than having none.
- **Item 4.** In both runs of step 13, for every cheap model, with the switch off and on: the report's "Missed items" is 0, and no chat lists a wrong or missing date. Chats that failed are left out, as the report already does when it compares chats. A chat where the chat model calls `recordFood` for no food counts too, although no check runs in it.
- **Item 5.** For each cheap model, in each run, "Fully correct chats (%)" is higher with the switch on than with it off.

Since D17 (2026-11-18) the name and count check is off in every run. Until [#118](https://github.com/phanmi/tyler_agent/issues/118) decides otherwise, item 4's "no missing food" rests on the chat model and on `recordFood` taking a list, with no check behind them.

The requirements that rest on this check count as partially met: the check is implemented and tested, and it is not switched on. Proving that it works comes before switching it on. That needs complex eval cases designed for it, which the maintainer decided on 2026-11-18 is out of scope for #113. [#118](https://github.com/phanmi/tyler_agent/issues/118) holds that work.

Since D29 (2026-11-21) the content check is forced off in every chat of the app, with its code kept. Item 5 was not met, and D29 was decided from that: in the [many-foods run of 2026-11-21](evals/verification-overhaul/2026-11-21-content-check.md), fully correct chats with the check on were level on `gpt-6-luna`, higher on `claude-haiku-5-5` and lower on `deepseek-flash`. Item 1's Settings switch was removed with the check (D30), so nothing in the app turns the check on or off.

### How the design meets #113

This table checks the scope of #113. The [acceptance criteria](#how-the-acceptance-criteria-are-met) are checked below the proposals.

| #113 asks for | How the design meets it | Met? |
|---|---|---|
| Each rejection's reason in the eval report | The report lists each check's verdict and reason (P7) | Yes |
| A check that does not judge one food against a message that lists many | The name and count check sees every food of the message at once. The content check sees one food and no chat. | Yes |
| No record that holds the whole meal | A record named after a meal, such as "dinner", is an incorrect name: it is removed and the foods it stood for are added as missing | Partially met: implemented and tested with the name check on, and off in every run since D17. The full run of 2026-11-18 had no such record. |
| A refusal does not drop a food the user listed | A failed check is corrected, not refused, and each correction runs once (D5). Only a food the name check calls incorrect is removed. | Yes |
| Cost and wait | One name check and one date check per message, one content check per food, and at most one correction each (D5). The content checks run at the same time (P6), at most 8 at once (D20). | Measured in step 13 |
| One tool that checks only the date | Verify date, then correct date, on each food's date (P2), after the name check has added any missing food (P1) | Yes |
| Some checks in code instead of a model | Python removes incorrect foods when the name check is on, and sends a food with no date to the date correction (D16) | Yes |
| A converted unit is not accepted (from #104) | The name check compares each food's amount and unit with the message, and the correction restores the user's unit (D4) | Partially met: implemented and tested with the name check on, and off in every run since D17 |
| Whether the date and unit checks run with the switch off | The date check always runs; the switch turns off only the content check (D3). The unit check is part of the name check, which is off in every run since D17. | Dates: yes. Units: partially met, as the row above |
| User-stated values always win (#96) | The content check is shown the conversation and the tool results, and treats a stated value as right (D15). Its correction keeps every stated value too (D1). | Yes |
| A Settings switch, saved, sent in the `run` payload, protocol version 3 (#98) | The switch turns off the content check (D3). It is saved as the first agent setting (D7) and sent in the `run` payload of protocol version 3, moved from version 2 (D8). | Superseded by D30: the switch and its storage were removed. Protocol version 3 still carries the value, and Java sends `false` in every `run`. |
| With the switch off, no content check or food correction runs (#98, reworded) | With the switch off, the date check still runs (D3). The name check is off in every run since D17. | Since D29 there is no switch: no chat of the app runs the content check or its correction, and the date check still runs. |
| Checks see the tool results a record was based on (#100) | Every check and every correction gets this run's tool results (D6, P9, D15) | Yes |
| Tool results cannot change what a check is asked to do (#100) | Tool results stay inside the JSON the checks read (P8) | Yes |
| A failed check saves nothing from the message (#101) | No food is saved until every check has run, and the whole list is saved in one Java transaction (D9) | Yes |
| One list of foods per message | `recordFood` takes every food of the message in one call (D2) | Yes |
| What result on the baseline runs counts as done | Stated under [What counts as done](#what-counts-as-done) | Yes |

### Design decisions

D1 to D9 were answered on 2026-11-17. D10 to D14 came up when step 3 started and were answered the same day. D15 and D16 came from the first eval run of the new checks, and D17 from the full run of 2026-11-18. D18 to D22 are the maintainer's answers of 2026-11-18 to review findings on PR #117. D23 to D25 came up when step 8 started and were answered on 2026-11-20. D26 to D28 came up when step 9 started and were answered on 2026-11-21. D29 and D30 came from the many-foods run of 2026-11-21 and were answered the same day. Where these decisions and the proposals differ from the sketch above, they take precedence; for example, P1 runs the name check before the date check.

| | Question | Recommended | Other options | Steps | Answer               |
|---|---|---|---|---|----------------------|
| D1 | How are values the user stated kept? | The content check stays without the chat. The food correction sees the chat and keeps every value the user stated, and a corrected food is saved without a second content check. | `recordFood` marks which values the user stated, and the content check skips them. Or the content check also sees the user's message. | 5, 6 | recommended, then replaced by D15 |
| D2 | Where does the list of foods come from? | `recordFood` takes every food of the message in one call, as a `foods` list. | Python collects all `recordFood` calls in one model response, which changes the agent loop. | 2 | recommended          |
| D3 | What does the Settings switch turn off? | Only the content check, as the design's note reads. The date and name checks always run, and #98's "no extra model calls when off" is reworded. | Every check, as #98 says. Or one switch per check. | 5, 8, 9, 10 | recommended          |
| D4 | Does "count" mean how many foods, or how much of each? Where are amount and unit checked? | The name check also gets each food's amount and unit, and reports those that differ from the message, including a converted unit. The correction changes only those two fields. | A separate amount-and-unit check. | 3 | recommended          |
| D5 | How often does a check run? | Each check runs once per message, and each correction once. Corrected values are saved without another check, and an unusable correction keeps the values it was meant to fix. Only the name check removes a food. | Check corrections again up to a limit, then save or refuse. | 3, 4, 5 | recommended          |
| D6 | How do checks see tool results? | The name check and every correction that sees the chat also get this run's tool results, so they judge what the chat model actually read. These also show the foods an earlier `recordFood` call in the run saved. | As the design says: the name check calls `readFile` itself. | 2, 3 | recommended          |
| D7 | Where is the switch saved? | As the first agent setting from #82, with its own model, service, controller and file, so agent behavior stays out of the provider settings. | A field in `LlmSettings`: about half the code, but "Reset provider settings" would also reset it. | 9, 10 | recommended          |
| D8 | How is the contract version kept? | Move `contracts/agent/v2/` to `v3/` with `git mv` and edit it in place, so the PR shows only the real changes. Version 2 stays in git history, and the rule in `docs/AGENT_PROTOCOL.md` changes. | Keep a full copy: about 5,000 lines of fixtures. | 8 | recommended          |
| D9 | How is the list saved? | A new Java operation saves every food of the message in one transaction, added in protocol version 3. | Save one food at a time, and when Java rejects one, tell the user which were saved. | 9 | recommended          |
| D10 | What happens when a check's answer cannot be used: a text reply, the wrong tool, several calls, or arguments that do not fit? D5 does not say. | Ask once more. If the second answer is also unusable, the check changes nothing, the foods go on to the next check, and a warning is logged without any food or chat text. The check tools are offered, not forced, so this happens now and then, and the baseline showed that dropping foods does more harm than leaving them unchecked. | Fail the chat. Or treat every food as rejected. | 3, 4, 5 | Content check: recommended. Name check and date check: ask again up to 3 times, then fail the chat with an error that says the model could not complete the check. |
| D11 | D4 says an amount correction changes only the amount and unit. But a food's nutrition values were estimated for the amount it had. If "2 eggs" was saved as 3, changing only the amount leaves the nutrition of 3 eggs, and with the content check switched off nothing fixes it. | The amount correction rewrites the amount, the unit and the nutrition values, keeping any value the user stated. The name and date stay. | Only the amount and unit, as D4 reads, leaving the nutrition to the content check. | 3 | recommended |
| D12 | Is a correction one request for each food, or one for all the foods a check flagged? | One request for each food, all sent at the same time. Each answer is one food record, so it cannot be matched to the wrong food. | One request for all of them: fewer requests, but each answer has to be matched back to its food. | 3, 5 | recommended |
| D13 | `verifyFoodList`, `verifyFoodDates` and `correctFoodDates` are each sent every food of the message at once, and their answer names only some of them, such as the one whole-meal record to remove. How does the answer say which food it means? The other two tools handle one food at a time, so the question does not come up for them. | By the food's number. Python numbers the foods when it lists them for the model, and the answer gives the number. Two foods can share a name, such as eggs at breakfast and at lunch, and the model may not repeat a name exactly. | By the food's name, which Python then has to find in the list. | 3, 4 | recommended |
| D15 | D1 kept the conversation from the content check. Should the check see what the user said? | Decided by the maintainer on 2026-11-17, after the first eval run, in place of D1's answer: the content check is shown the whole conversation and the run's tool results, the same as the other checks, because a user can state calories and nutrition values, in a message or in a file. The check must respect whatever the user gives. | Only the latest user message. Or the conversation without the tool results. | 5 | decided |
| D16 | What does the date check do with a food that has no date? | Decided by the maintainer on 2026-11-17, after the first eval run: the date check never accepts one. Python sends a food with a missing or blank date to the date correction, whatever the check answers. | Leave it to the check's answer. | 4 | decided |
| D14 | D10 covers the verify tools. What happens when a correction's answer cannot be used? D5 says the food keeps its old values, but a missing food has none, so it would stay missing without anyone being told. | A correction follows the rule of its check. For the name check and the date check: ask again up to 3 times, then fail the chat. For the content check: ask once more, then keep the food's values. | Every correction keeps the old values, as D5 reads. | 3, 4, 5 | recommended |
| D17 | In the full run of 2026-11-18 the name and count check changed a record in 4 of 1,206 chats, for one model request in every `recordFood` call. Should it run? | Decided by the maintainer on 2026-11-18: no. It is switched off in every run (`RunSettings.check_names`, off by default) and then makes no request. Its code and tests are kept, so the requirements that rest on it count as partially met. It is switched on only after it is proved to work. Proving that needs complex eval cases designed for it, which the maintainer decided is out of scope for #113; [#118](https://github.com/phanmi/tyler_agent/issues/118) holds that work. | Keep it running. Or remove its code. | 3, 6 | decided |
| D18 | A `correctFood` answer can leave a field out, give null, or give a negative number, because DeepSeek's tool calls are not strict. Python copied the gap over a value that could be saved, and Java then rejected the food (review finding F1). What makes a correction unusable? | An answer with a field missing or null is unusable, and is asked again as D10 and D14 say. A negative number stays Java's rule. | Also treat a negative number as unusable, which repeats one of Java's rules in Python. Or leave it. | 3, 5 | Decided by the maintainer on 2026-11-18: also treat a negative number as unusable. |
| D19 | `correctFoodDates` accepted any text as a date, such as "yesterday", and Java then rejected the food (F2). What must a corrected date be? | Check only its shape, `YYYY-MM-DD`. Whether that day exists stays Java's rule. | Also check in Python that it is a real calendar date, which repeats Java's rule. Or leave it to Java. | 4 | Decided by the maintainer on 2026-11-18: also check that it is a real calendar date. |
| D20 | The `foods` list has no size limit, so a long list sent every content check, or every correction, at the same moment. The providers do not retry, so one rate-limit error failed the run (F4). | At most 8 of a check's questions are asked at the same time, as a named constant. The rest wait their turn. | Leave it until an eval run shows a rate limit. Or limit how many foods one call may carry. | 3, 5 | recommended |
| D21 | The tool result told the chat model not to record a left-out record again, and no longer to tell the user. A check that wrongly removes a real food then goes unnoticed (F6). | Restore the instruction: tell the user which records were left out and why. A duplicate or a whole-meal record is then mentioned too. | Keep the note as it was. Or also list the foods whose nutrition values could not be corrected. | 6 | recommended |
| D22 | The date check and the content check do not depend on each other. Should they run at the same time (F7)? | Keep them one after the other, and decide with the measured time of a multi-food message. | Run both after the name and count check, and merge the dates from one with the nutrition values from the other. | 6 | recommended. The maintainer added: "having tools running at the same time should be put later in the design." |
| D23 | What is the operation that saves a list of foods (D9) called, what does it carry, and does `food.insert` stay? | `food.insert_all`. Its input is `{foods: [food, ...]}` with at least one food, and its result is `{foods: [{id, food}, ...]}` in the input's order. It replaces `food.insert`, which nothing would call after step 9. | Add it and keep `food.insert`, an allowed write that nothing calls. Or keep the name `food.insert` and change its input and result to lists. | 9 | recommended |
| D24 | What is the failure code for a check the model could not complete (D10), and in which category? | `MODEL_ANSWER_UNUSABLE`, in the existing `provider` category. A new code needs a new protocol version, so the name is general and another tool can raise it. | `FOOD_CHECK_INCOMPLETE`, also in `provider`, named for the only thing that raises it today. | 8, 9 | recommended |
| D25 | Step 8 put the operation in the schema and step 9 gave it its Java handler. But Java does not start unless every operation in the schema has a handler. Where does the operation go? | Whole into step 9: the schema, the fixtures, the handler, the one-transaction save, and `recordFood` using it. Step 8 ends with the failure code. | Pull the handler and the one-transaction save forward into step 8. | 8, 9 | recommended |
| D26 | What does the user get when a run fails with `MODEL_ANSWER_UNUSABLE` (D10, D24)? | The text "The model could not complete a required check. Please try again", with HTTP 500 like every other failure in the `provider` category. | The same text with HTTP 502, because the fault is in the model's answers and not in the server. The rule that gives a failure its status by category then has an exception for this code, in the Java and the Python session. | 9 | Decided by the maintainer on 2026-11-21: HTTP 502, with the proposed text. |
| D27 | How is the switch read and saved (D7), and what happens when its file cannot be read? | `GET` and `PUT /api/agent/settings` with `{"checkNutrition": true}`, saved in `agent-settings.json`. No file means on. A missing or non-boolean value gets HTTP 400 naming the field. A file that cannot be read or parsed fails the settings request and the chat with HTTP 500 until the switch is saved again, as `llm-settings.json` does. | Treat an unreadable file as on and log a warning. A user who switched the check off would then pay for it without being told. | 9 | recommended |
| D28 | `IFoodService.insertFood` is called only by the bridge handler that `food.insert_all` replaces. What happens to it? | `insertFoods`, which saves a list in one transaction, replaces it, and its tests move. | Keep both, with only tests calling `insertFood`. | 9 | recommended |
| D29 | In the many-foods run of 2026-11-21 the content check left fully correct chats unchanged on `gpt-6-luna`, raised them by 4 of 60 on `claude-haiku-5-5` and lowered them by 3 of 60 on `deepseek-flash`, for more than twice the model requests in each chat. Should it run? | Decided by the maintainer on 2026-11-21: no. The content check is forced off and not deleted: the check, its correction and their tests stay in the code. The reason given: even on the low-cost models, the nutrition values the chat model estimates from the first prompt are good enough. [#113](https://github.com/phanmi/tyler_agent/issues/113) records the run. | Keep the Settings switch. Or remove the check's code. | 5, 9, 10 | decided |
| D30 | How is the content check forced off, and what happens to the Settings switch of steps 9 and 10? | The Settings card, the agent settings endpoint and its file are removed. Java sends `checkNutrition: false` in every `run`. The protocol field and the Python check stay, so the eval harness can still switch the check on. | Remove only the Settings card, and keep the backend setting unused. | 8, 9, 10 | recommended |

### Proposed additions

Unless you object, the implementation also does these.

| | Proposal | Why | Steps | Answer |
|---|---|---|---|-------|
| P1 | The name check runs first, then the date check, then the content check. | A food the name check adds also has its date checked. | 6 | accept |
| P2 | The date check judges each food's date. The date correction changes only the dates of the foods it names. | One message can cover several days. | 4 | accept |
| P3 | The name check looks for the foods the current message asks to record. A food that an earlier message, or an earlier `recordFood` call in this run, already recorded is not missing. | A food is not saved twice. | 3 | accept |
| P4 | The food correction changes only nutrition values. The name, amount, unit and date stay. | A correction cannot undo what the earlier checks fixed. | 5 | accept |
| P5 | The five check tools are offered only to the check requests. The chat model sees only `recordFood`. | The chat model cannot skip a check. | 3, 4, 5 | accept |
| P6 | The content checks of different foods run at the same time. | A multi-food message takes less time. | 5 | accept |
| P7 | The eval report lists each check's verdict and reason for every food it rejected. | #113 asks for rejection reasons. | 7 | accept |
| P8 | Tool results stay inside the JSON the checks read, as untrusted text. | A file's text cannot change what a check is asked to do (#100). | 2, 3 | accept |
| P9 | The date check also gets this run's tool results. | D6 gives them only to the name check and the corrections. A date can come from a file, such as a food log with dated entries, and without the file the date check would reject that date and the correction would replace it with a guess. | 4 | agree |
| P10 | The switch is named for what it turns off: "Check nutrition values" in Settings, and `checkNutrition` in the `run` payload in place of #113's `verifyFood`. | After D3 it no longer turns off all verification, so `verifyFood` would mislead. | 8, 9, 10 | agree |

### Code design for the checks

This is how steps 3 to 6 are built.

**Modules.** All of them sit in `tools/builtin/food_record/`, beside `recordFood`'s adapter. Nothing outside that package changes.

| Module | What it holds | Step |
|---|---|---|
| `foods.py` | `FoodList`: the foods of one `recordFood` call, held in memory while the checks run. It never changes in place: removing a food, adding one, or setting a date, an amount or nutrition values returns a new list. It also keeps a finding for each thing a check did, with the reason, for the tool result and the eval report. | 3, 7 |
| `asking.py` | `ask`: one model request that offers one tool, and returns the arguments of exactly one call of that tool, or nothing. `must_ask` fails the chat when no answer can be used, and `together` asks several questions at the same time, at most 8 at once (D20). Also `Evidence`: what a check judges the foods against, read once for the call: today's date, the conversation, and the run's earlier tool calls. It writes the conversation and the calls into every prompt in the same words. Also `numbered_texts`, which reads the entries of an answer that point at foods by number. | 3 |
| `correction.py` | The correct food tool: one request that writes one food record. It uses `recordFood`'s own schema for one food, the existing parser, and the part of `recordFood`'s description that says how one food is written. | 3 |
| `name_check.py` | The verify food name and count tool, and what it does with the answer. | 3 |
| `date_check.py` | The verify date and correct date tools. | 4 |
| `content_check.py` | The verify food content tool, run for each food, at most 8 at the same time. | 5 |
| `checks.py` | Runs the checks in P1's order and returns the final list. | 6 |
| `verification.py` | The current verifier. Removed. | 6 |

**The five tools.** Each is offered only to its own request (P5).

| Tool | What the model is shown | What it answers |
|---|---|---|
| `verifyFoodList` | The conversation, the earlier tool calls, and each food's number, name, amount and unit | `missing`: foods the message asks to record that are not in the list, each as the user described it. `incorrect`: the numbers of foods the message does not ask to record, such as an invented food, a whole meal, a food listed twice or one an earlier call already saved, each with a reason. `wrongAmount`: the numbers of foods whose amount or unit differs from what the user said, each with a reason. |
| `correctFood` | The conversation, the earlier tool calls, today's date, what to write, and the record being corrected, if there is one | One food record, with every field given and no negative number (D18) |
| `verifyFoodDates` | The conversation, the earlier tool calls, today's date, and each food's number, name and date | `wrong`: the numbers of foods whose date is wrong, each with a reason. Python adds every food with no date (D16). |
| `correctFoodDates` | The same, with those reasons | The corrected date for each of those numbers, a real day written as `YYYY-MM-DD` (D19) |
| `verifyFoodNutrition` | The conversation, the earlier tool calls, and one food record without its date (D15) | Whether its nutrition values match what the user stated and are otherwise plausible, with a reason |

**What Python keeps from a `correctFood` answer.** The model writes a whole record each time, and Python takes only the fields that correction may change.

| The correction is for | Python keeps |
|---|---|
| A missing food | The whole record. Its date is checked next (P1). |
| A wrong amount or unit | The amount, the unit and the nutrition values (D11) |
| Implausible nutrition values | The nutrition values (P4) |

**Model requests for a message with n foods.**

| Check | Requests | Extra requests |
|---|---|---|
| Name and count | 1 | 1 for each missing food and each wrong amount |
| Date | 1 | 1 if any date is wrong |
| Content, with the switch on | n, at most 8 at the same time (D20) | 1 for each food it rejects |

**An answer that cannot be used.** This is a text reply, the wrong tool, several calls, or arguments that do not fit the tool (D10, D14). It is also a `correctFood` record with a field missing or null or with a negative number (D18), and a `correctFoodDates` date that is not a real day written as `YYYY-MM-DD` (D19).

| Request | What happens |
|---|---|
| `verifyFoodList`, `verifyFoodDates`, and their corrections | Asked again, up to 3 more times. Then the chat fails with an error that says the model could not complete the check, and nothing is saved. |
| `verifyFoodNutrition` and its correction | Asked once more. Then the food goes on with the values it has. |

The error has a failure code of its own, `provider/MODEL_ANSWER_UNUSABLE` (D24). The contract allows a new code only in a new protocol version, so it came with version 3 in step 8. Java answers it with HTTP 502 and the text "The model could not complete a required check. Please try again" (D26). When the run saved something before the call that failed, the text does not ask to try again. It says that some foods may already be saved, and to check the Calendar first (R8 of the second code review).

**Tests.** Steps 3 to 5 test each check on its own, with a real tool context and a scripted model, as the current verifier's tests do. Step 6 puts the checks into `recordFood` and adds the tests through the real agent loop, registry and runtime.

**Adding another check** takes one new module, with its tool and its prompt, and one line in the ordered list in `checks.py`. The registry, the agent loop, `ToolContext` and the other checks do not change.

### How the acceptance criteria are met

#113's criteria were reworded on 2026-11-17 to match D1 to D9 and P1 to P10. **Met** means the steps named meet the criterion as #113 now words it.

**Superseded by D30** marks a criterion about the Settings switch, which D30 removed on 2026-11-21. #113 was reworded the same day. Two criteria replace them, in the two rows after the superseded ones, and #113 lists the superseded criteria in one line.

| #113 criterion | How it is met | Steps | Status |
|---|---|---|---|
| A design is approved before code. It says what counts as done on the baseline run, and how it meets #98, #100, #101 and #104's eval. | This plan: [What counts as done](#what-counts-as-done), the scope table above, D1 to D9 and P1 to P10. | 1 | Met on 2026-11-17 |
| A `--cheap-models --category many-items` run is reported beside the baseline. | The eval's "off" mode means the content check is off (D3). The date check runs in both modes. The name and count check ran in both modes in the run of 2026-11-18, and runs in neither since D17. So the baseline's no-check numbers come only from the 2026-11-16 report. | 7, 13 | Met. The descriptions of PR #117 and PR #121 report the runs of 2026-11-18 and 2026-11-21, and both are written up under `docs/evals/verification-overhaul/`. |
| No food is saved until every check that is switched on has run on it. A corrected value is saved without a second check. | The list is saved in one operation after the last check (D9), and each correction runs once (D5). | 6, 9 | Met |
| Python tests drive the new behavior through the real agent loop, tool registry and `recordFood`. | Each check's tests run through them. | 3 to 6 | Met |
| One tool checks only that a record's date is correct, with an invented date tested. | Verify date and correct date (P2). | 4 | Met |
| A unit converted from the user's is not accepted, with the grande latte tested. | The name check reports 16 fl oz, and the correction restores "grande" (D4). | 3 | Partially met: implemented and tested with the name check on, and off in every run since D17. |
| A `--cheap-models` run of #104's four categories is reported beside #116's run. | Wrong-date chats, and the unit `deepseek-flash` saves for `starbucks-latte`. | 13 | Met by the full run of 2026-11-18, which had the name and count check on. The maintainer decided on 2026-11-22 that this run stands, and that these categories are not run again with that check off. |
| Settings shows a nutrition check switch, "Check nutrition values", that is on when nothing has been saved, and it persists across a restart. | The first agent setting (D7) and the Settings page (P10). | 9, 10 | Superseded by D30: the switch was removed |
| With the switch off, no content check or food correction runs. The name and date checks still run. | `RunSettings` built from the `run` payload switches the content check (D3). Since D17 the name check runs in neither position. | 5, 8 | Superseded by D30: there is no switch. Java sends `checkNutrition: false` in every `run`, so no chat of the app runs the content check or its correction, and the date check still runs |
| A change applies to the next chat, and Java tests prove the `run` payload carries the saved value. | `PythonAgentService` reads the setting when a chat leaves the queue. | 9 | Superseded by D30: the switch was removed |
| A missing or non-boolean value gets HTTP 400 naming the field, and the frontend shows it. | The settings controller and the Settings page. | 9, 10 | Superseded by D30: the switch was removed |
| The model's file tools refuse the file that stores the switch. | The setting's service implements `IApplicationOwnedFiles`. | 9 | Superseded by D30: the switch was removed |
| The version 3 schema and fixtures reject a `run` without `checkNutrition` or with a non-boolean, the fixture tests agree, and a version 2 peer fails with `UNSUPPORTED_VERSION`. | Version 2 moved to version 3 and edited in place (D8). | 8 | Met |
| Frontend tests cover the default, a save and a failed save. | Settings page tests. | 10 | Superseded by D30: the switch was removed |
| No chat of the app runs the content check or its food correction: Java sends `checkNutrition: false` in every `run`, and Java tests prove it. The date check still runs. | `PythonAgentService` sends the constant `CHECK_NUTRITION` (D30). `PythonAgentServiceTest` asserts the payload, and `AgentPersistenceIntegrationTest` runs a chat through the real worker. | 9 | Met |
| The content check, its food correction and their tests stay in the code, and the eval harness can run a case with the check on. | `RunSettings.check_nutrition` still switches the check, and the eval harness runs every case with it off and on (D29). | 5, 7 | Met |
| A food recorded from a file the chat model read can be accepted and saved. | The name check, the date check and the corrections see the file's content (D6, P9). | 2, 6 | Met |
| The name check, the date check and the corrections receive this run's tool results, and no keys. | A general part of `ToolContext` (D6, P9), and a test that no check request carries a key. Since D15 the content check receives them too, so #113's wording needs "every check". | 2, 3, 4, 5 | Met |
| A food that matches neither the conversation nor a tool result is not saved. | The name check calls it incorrect, and Python removes it. | 3 | Partially met: implemented and tested with the name check on, and off in every run since D17. |
| Python tests drive the file-based flow through the real agent loop, tool registry and `recordFood`. | The file-based test in step 6. | 6 | Met |
| Adding another tool needs no change to the registry or the agent loop. | Tools read this run's tool results through a general part of `ToolContext`, `earlier_calls` (D6). The registry fills it in for every call, so no tool needs wiring of its own and the agent loop did not change. | 2 | Met |
| A failure while checking a multi-food message saves none of its foods. | Nothing is saved until every check has run (D9). | 6 | Met for one `recordFood` call. An earlier call of the same message stays saved; the next row covers it. |
| Resending after such a failure creates no duplicates when nothing of the message was saved. When a check could not be completed after an earlier `recordFood` call of the chat saved, the app does not ask for a resend: the error says that some foods may already be saved, and to check the Calendar first. | Nothing of the failed call was saved, so nothing of it is saved twice. When a check gets no usable answer (`MODEL_ANSWER_UNUSABLE`) after an earlier call of the same message was saved, the error says so and does not ask the user to send the message again (R8 of the second code review). Not covered: a check that fails another way after a save, such as on a provider error, still answers "Please try again later", and a user who sends the message again in spite of the warning still saves the earlier call's foods twice. [#57](https://github.com/phanmi/tyler_agent/issues/57) is about a later call that fails after an earlier one saved. | 6, 9 | Met as reworded on 2026-11-22, for a check that could not be completed |
| Java saves a message's foods in one transaction: when it rejects one, none is saved. The version 3 contract covers the new operation. | The operation `food.insert_all`, with a bridge handler and service method of its own (D9, D23). | 9 | Met |
| The time a multi-food message takes with the checks on is measured before and after. | The eval reports each chat's reply time. | 13 | Met. The [content check eval](evals/verification-overhaul/2026-11-21-content-check.md#the-time-a-message-with-many-foods-takes) has the times beside the baseline's. |
| Python tests cover a failure and a cancellation between checks through the real agent loop and runtime. | The failure and cancellation tests in step 6. | 6 | Met |
| `AGENT_OPERATIONS.md` describes the new behavior, including what each check sees. | What each check sees. | 11 | Met |
| `AGENT_PROTOCOL.md`, `DEVELOPMENT.md`, `BACKEND_STANDARDS.md` and the README describe protocol version 3, and say that the nutrition values are not checked a second time. | Including version 3 and D8's rule change. #113 worded this criterion for the switch until D30. | 11 | Met |
| The Python checks, `mvn test` and `npm run check` pass. | Run after every area is done. | 12 | Met |

## Steps

| Step | Work | Status | Evidence |
|---|---|---|---|
| 1 | Get the design approved: D1 to D9 answered, what counts as done stated, and #113's wording updated where the design changes it. | Done | D1 to D9 and P1 to P10 answered, #113 updated to match, and what counts as done stated and agreed, on 2026-11-17. |
| 2 | `recordFood` takes the message's foods as a list, held in memory for the call (D2). Tools can read this run's tool results through a general addition to `ToolContext`, not one for `recordFood` alone (D6). The current verifier still runs on each food until step 6. | Done | See the execution record for 2026-11-17. |
| 3 | Shared check machinery: one request offering one check tool, and an answer that cannot accept by accident. Then the name and count check, with amount and unit (D4, P3): the model adds missing foods and Python removes incorrect ones. Tests: a whole-meal record is replaced by its foods, a missing food is added, a converted unit is corrected, and a food from an earlier message is not added again. | Done | See the execution record for 2026-11-17, step 3. |
| 4 | Date check and date correction (P2), with this run's tool results (P9). Tests: an invented date is corrected, foods on different days keep their own dates, and a date taken from a file the chat model read is kept. | Done | See the execution record for 2026-11-17, step 4. |
| 5 | Content check and food correction (D1, P4), one per food, run at the same time (P6), switched by `RunSettings` (D3). Tests: the user's 1900 kcal banana is kept, and an implausible estimate is corrected. | Done | See the execution record for 2026-11-17, step 5. |
| 6 | The checks run in P1's order in place of `verified_food`, and no food is saved until every check has finished. The old verifier, `submitFoodVerdict` and their tests are removed. Tests through the real agent loop and runtime: a failure or a cancellation between checks saves nothing, and a food from a file the chat model read is saved. | Done | See the execution record for 2026-11-17, step 6. |
| 7 | Eval harness: record each check's verdict and reason, and list rejections in the report (P7). | Done | See the execution record for 2026-11-17, step 7. |
| 8 | Protocol version 3 (D8): the switch field in `run`, and a failure code for a check the model could not complete (D10, D24). The operation that saves a list of foods moved to step 9 (D25). Update the schema, the fixtures, `MessageValidator`, `MessageCodec`, the Python validation, the version constants, and the contract paths in `pom.xml`, `worker.spec`, `tests/desktop-worker.spec` and `tests/conftest.py`. A version 2 peer fails with `UNSUPPORTED_VERSION`. The runtime builds `RunSettings` from the payload. | Done | See the execution record for 2026-11-20, step 8. |
| 9 | Java: the switch's storage (D7), implementing `IApplicationOwnedFiles`, which rejects a missing or non-boolean value with HTTP 400 naming the field. `PythonAgentService` sends the value when a chat leaves the queue. The operation `food.insert_all` saves a list of foods in one transaction and replaces `food.insert` (D9, D23, D25): version 3's schema and fixtures, a bridge handler and a service method. `recordFood` uses it, and the eval's `FakeJava` answers it. The new failure code gets its own message for the user (D10, D24, D26). | Done; the switch's storage was then removed by D30 | See the execution record for 2026-11-21, step 9, and for D29 and D30. |
| 10 | Frontend: the switch in Settings, its description of the extra model calls, saving, and showing a failed save. Tests cover the default, a save and a failed save. | Done, then removed by D30 | See the execution record for 2026-11-21, step 10, and for D29 and D30. |
| 11 | Docs: `AGENT_OPERATIONS.md` (what each check sees), `AGENT_PROTOCOL.md`, `DEVELOPMENT.md`, `BACKEND_STANDARDS.md`, the README's Settings section, and the eval README. | Done | See the execution record for 2026-11-21, step 11. |
| 12 | Checks: Python, `mvn clean test` and `npm run check`. Show that each new regression test catches its bug. Run `/code-review` on the branch and resolve what it finds. | Done | See the execution record for 2026-11-21, step 12. |
| 13 | Paid eval runs, after your go-ahead: `--cheap-models --category many-items`, and `--cheap-models` on #104's four categories, each compared with its baseline. Measure how long a multi-food message takes, before and after. Check the results against [What counts as done](#what-counts-as-done), and write them up under `docs/evals/`. | Done | The full run of 2026-11-18 holds both sets and is written up as the [verification overhaul eval](evals/verification-overhaul/2026-11-18-verification-overhaul.md). The many-foods run with the name and count check off was made on 2026-11-21 and is written up as the [content check eval](evals/verification-overhaul/2026-11-21-content-check.md), which gives the time a multi-food message takes. #104's four categories were not run again with the name and count check off: the maintainer decided on 2026-11-22 that the run of 2026-11-18 stands for them. |
| 14 | Open the PR with the template filled in, and tick the #113 criteria that were checked. | Done | PR #121 was opened on 2026-11-21 for steps 8 to 12, before step 13 was finished. A second PR holds the write-up of step 13 and closes #113. See the execution record for the second code review and for 2026-11-22. |

## Decisions

| Date | Decision | Source |
|---|---|---|
| 2026-11-17 | Split the current verification tool into separate tools. | Maintainer, when this plan was set up |
| 2026-11-17 | D1 to D9: the recommended option for each. P1 to P8 accepted. | Maintainer, in the tables under Design |
| 2026-11-17 | P9 and P10 accepted. #113's scope and acceptance criteria updated to match the design, with one new criterion for D9's one-transaction save. | Maintainer |
| 2026-11-17 | D10: an unusable answer from the name check or the date check is asked again up to 3 times, then the chat fails; from the content check it is asked once more, then the food goes on unchanged. D11 to D14: the recommended option for each. | Maintainer |
| 2026-11-17 | D15 and D16, after the first eval run of the new checks: the content check is shown the conversation and the tool results and respects every value the user stated, and the date check never accepts a food with no date. | Maintainer |
| 2026-11-17 | What counts as done: every check implemented and the content check switchable from Settings, every criterion met, the cheap models improve, no wrong date and no missing food, and better results with the content check on. How the eval is read against it, as recommended. | Maintainer |
| 2026-11-18 | The full run is read without `some-almonds`: its message can be taken as general chat that calls for no `recordFood`. The run is marked a temporary success. | Maintainer |
| 2026-11-18 | D17: the name and count check is switched off in every run, with its code kept, until [#118](https://github.com/phanmi/tyler_agent/issues/118) shows with new classes of eval cases whether it does important work. | Maintainer |
| 2026-11-18 | The requirements that rest on the name and count check are partially met: it is implemented, and it is switched on only after it is proved to work. Proving that needs complex eval cases designed for it, which is out of scope for #113 and goes to [#118](https://github.com/phanmi/tyler_agent/issues/118). | Maintainer |
| 2026-11-18 | Review findings F1 to F10 on PR #117. F1 and F2: the stricter option, so Python also rejects a negative number in a correction and a corrected date that is not a real calendar date (D18, D19). F4, F6 and F7: as proposed (D20 to D22). F3: one check for each `recordFood` call stays, and the eval run is read for foods saved twice. F5, F8, F9 and F10: the proposed code changes. | Maintainer, in the PR's review threads |
| 2026-11-20 | D23 to D25, when step 8 started: `food.insert_all` replaces `food.insert`, the failure code is `provider/MODEL_ANSWER_UNUSABLE`, and the operation moves whole into step 9. | Maintainer |
| 2026-11-21 | D26 to D28, when step 9 started: `MODEL_ANSWER_UNUSABLE` is answered with HTTP 502 and its own text, the switch is saved through `/api/agent/settings` and an unreadable file is an error, and `insertFoods` replaces `insertFood`. | Maintainer |
| 2026-11-21 | The seven findings of the code review in step 12: R1 A, R2 B, R3 A, R4 A, R5 A, R6 A and R7 A. The workout plan's part of R1 goes to [#119](https://github.com/phanmi/tyler_agent/issues/119), and R2 to [#120](https://github.com/phanmi/tyler_agent/issues/120). | Maintainer |
| 2026-11-21 | D29, from the many-foods run of 2026-11-21: the content check is forced off and not deleted. Even on the low-cost models, the nutrition values the chat model estimates from the first prompt are good enough. Recorded in [#113](https://github.com/phanmi/tyler_agent/issues/113)'s description. | Maintainer |
| 2026-11-21 | D30: option A. The Settings card, the agent settings endpoint and its file are removed, Java sends `checkNutrition: false` in every `run`, and the protocol field and the Python check stay. | Maintainer |
| 2026-11-21 | After D30, what R5 extracted has one user. Option A: `SandboxJsonFile`, `useLoadedSettings` and `SettingsLoadError` are deleted, and the provider settings code is what `main` has. | Maintainer |
| 2026-11-21 | #113's wording after D30 is approved and posted: how the check is forced off, the scope of #98, and two criteria in place of the five about the switch. | Maintainer |
| 2026-11-21 | The four findings of the second code review: R8 B, fixed in [PR #121](https://github.com/phanmi/tyler_agent/pull/121) because it blocks the release, and R9 A, R10 A and R11 A. | Maintainer, in the PR's threads; R10 in chat |
| 2026-11-22 | The criterion about resending is reworded, option A: the app does not ask for a resend after an earlier `recordFood` call of the chat saved, and R8's warning meets the criterion. Making a resend unable to duplicate is not part of #113. | Maintainer |
| 2026-11-22 | #104's four categories are not run again with the name and count check off. The run of 2026-11-18, reported in PR #117, stands for that criterion. | Maintainer |
| 2026-11-22 | #113 is closed by the PR that adds the write-up of the 2026-11-21 run. | Maintainer |

Decisions made before this plan are in #113's description.

## Execution record

_One entry per completed step or eval run, oldest first: the date, what changed, the checks or eval run and their results, and anything left open._

### 2026-11-17 Step 2: a list of foods, and the run's earlier calls

**What changed**

- `recordFood` takes `{"foods": [...]}`. Each entry has the shape one food had before. A missing, null or empty list goes back to the model, and every food's types are checked before any number is encoded. Its description tells the chat model to put every food of the message in one call and not to combine a meal into one entry.
- The result is `{"recorded": [...]}`, with each saved food in the released format. Foods the current verifier refuses are listed under `notRecorded` with the reason, beside an `error` that says they were not saved.
- The current verifier checks every food of a call before the first is saved, so a failed model request saves none of them. Foods are still saved with one `food.insert` each until step 9.
- `ToolContext.earlier_calls` holds every tool call the run finished before the current one, with its arguments and result. The registry (`ToolAdapters`) fills it in, so no tool needs wiring of its own and the agent loop did not change. It includes a call from the same model response and one the registry rejected.
- The eval runner counts the foods a call lists as not recorded. The report's "Items saved after a rejection" row is removed: one call now carries several foods, so the runner can no longer tell which food a model request belonged to. Step 7 replaces it with each check's verdict.
- `contracts/agent/v2/fixtures/tool-definitions.json` has the new definition, and `AgentPersistenceIntegrationTest` reads the saved food from `recorded`.

**Tests shown to catch their bug.** Each of these failed with its change removed and passed with it restored:

- `test_each_call_is_lent_the_calls_its_run_finished_before_it`, with the registry no longer passing `earlier_calls`.
- `test_wrong_types_are_rejected_before_number_limits_apply`, its new case, with foods encoded one at a time.
- `test_a_call_without_a_food_object_in_its_list_is_rejected`, the empty list, with an empty list allowed.
- `test_every_food_of_a_call_is_checked_before_the_first_is_saved`, with each food saved right after its own check.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 909 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. This includes `PythonToolContractTest` on the new definition, and `AgentPersistenceIntegrationTest`, which drives the real worker with list calls.
- Not run: `npm run check`, because the frontend did not change, and no eval run, because that is step 13.

**Left open**

- A chat model can still spread a message over several `recordFood` calls. Each call is then checked and saved on its own, until the name check reads `earlier_calls` in step 3.
- #113 says the agent loop changes once to provide the tool results. It did not need to.

### 2026-11-17 Step 3: the name and count check

**What changed**

- Four new modules in `tools/builtin/food_record/`: `foods.py`, `asking.py`, `correction.py` and `name_check.py`, as the code design describes. The check is not used by `recordFood` yet; step 6 puts the checks in.
- `verifyFoodList` is shown the conversation, the run's earlier calls, and each food's number, name, amount and unit. It answers with the missing foods, the records that do not belong and the records with a wrong amount, each pointing at a food by its number (D13).
- A record that does not belong is removed in Python and kept with its reason for the tool result. Each missing food and each wrong amount gets its own `correctFood` request, and they are sent at the same time (D12). An amount correction brings its nutrition values; the name and date stay (D11).
- An answer that cannot be used is asked again, 4 requests in all, and then the chat fails as a provider failure (D10, D14). If one correction fails, the others are cancelled before the failure reaches the caller.
- The conversation and the earlier calls reach every prompt as JSON text (P8).
- One limit was added that the design does not name: `verifyFoodList` may report 20 missing foods at most. A longer list is an unusable answer, so a confused model cannot start an unbounded number of correction requests.
- The shared test fakes moved from `test_food_verification.py` to `tests/support/food_fakes.py`, and the current verifier now imports `single_call` and `conversation_text` from `asking.py`, so there is one copy of each.

**Tests shown to catch their bug.** `tests/test_food_name_check.py` has 39 tests. Each of these failed with its behaviour removed and passed with it restored:

- An unusable answer asked again: with the question asked only once, 30 tests failed.
- `test_a_wrong_amount_brings_its_nutrition_and_the_name_and_date_stay`, with the old nutrition values kept.
- `test_a_record_that_does_not_belong_is_removed_without_correcting_its_amount`, with a removed record still corrected.
- `test_an_unusable_correction_is_asked_again_then_fails_the_run`, with a correction allowed to lack its nutrition values.
- `test_corrections_are_sent_at_the_same_time_and_kept_in_order`, with corrections sent one after another.
- `test_a_failed_correction_cancels_the_others_before_the_check_ends`, with the others left running.
- `test_the_check_and_its_corrections_see_the_runs_earlier_calls_as_data`, with a tool result pasted into the prompt as plain text.
- `test_an_unusable_answer_is_asked_again_and_never_read_as_nothing_wrong`, with a food number outside the list accepted.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 948 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. No contract or tool definition changed in this step; the run confirms the worker still starts and serves the integration tests with the new modules.
- Not run: `npm run check`, because the frontend did not change, and no eval run.

**Left open**

- Whether the check finds the right foods is the model's work, and only the eval run in step 13 measures it. These tests script the model's answers and prove what Python does with them.
- The through-the-loop tests, with each real provider class, come with step 6, when `recordFood` uses the checks.

### 2026-11-17 Step 4: the date check

**What changed**

- A new module, `date_check.py`, with the `verifyFoodDates` and `correctFoodDates` tools. It is not used by `recordFood` yet; step 6 puts the checks in.
- `verifyFoodDates` is shown today's date, the conversation, the run's earlier calls (P9), and each food's number, name and date. It answers with the foods whose date is wrong, each with a reason.
- When a date is wrong, one `correctFoodDates` request gives the new dates. It is shown only the foods the check named, with the reasons. Python changes the date and nothing else in a record (P2).
- The correction must give a date for every food the check named and for no other. Anything else is an unusable answer, so a food is never left with a date the check called wrong.
- An unusable answer from the check or the correction is asked again, 4 requests in all, and then the chat fails (D10, D14).
- A list with no foods, which the name check can leave, is not checked.
- Python does not judge whether a corrected date is a real calendar date. Java does that when the food is saved, as it does for a date the chat model writes.
- The helpers the check tests share moved to `tests/support/food_fakes.py`.

**Tests shown to catch their bug.** `tests/test_food_date_check.py` has 30 tests. Each of these failed with its behaviour removed and passed with it restored:

- A corrected date not written into the record: 17 tests failed.
- A correction that may leave out a food the check named.
- A correction that may change a food the check did not name. The first run of this removal passed every test, because the rule that the answer covers exactly the named foods already rejects an extra food. The guard still stops a number such as 1.5 being rounded to food 1, so that case was added, and it now fails.
- `true` read as food number 1.
- The check not shown the run's earlier calls.
- A list with no foods being checked.
- An answer without its list read as every date right.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 978 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. No contract or tool definition changed in this step.
- Not run: `npm run check`, because the frontend did not change, and no eval run.

**Left open**

- Whether the check finds a wrong date is the model's work, and only the eval run in step 13 measures it.

### 2026-11-17 Step 5: the content check

**What changed**

- A new module, `content_check.py`, with the `verifyFoodNutrition` tool. It is not used by `recordFood` yet; step 6 puts the checks in.
- `verifyFoodNutrition` is shown one food's name, amount, unit and nutrition values, and nothing else: no conversation, no tool result, no date and no other food. Every food is checked at the same time (P6).
- A food it rejects gets one `correctFood` request, which is shown the conversation and told that a value the user stated is right however unusual it looks. Python keeps only the nutrition values of the answer (P4), and they are saved without a second check (D1, D5). So the check cannot overrule a value the user stated.
- An unusable answer is asked once more. After that the food goes on with the values it has: an unusable check answer is read neither as a pass nor as a rejection, and an unusable correction keeps the food's values (D10, D14). This check never fails the chat on its own.
- `RunSettings.check_nutrition` switches the check, and is on by default. With it off, the check makes no request. `RunSettings.verify_food` still switches the current verifier until step 6 removes it.
- The sources text the check judges against moved from the current verifier to `content_check.py`, and the current verifier imports it, so there is one copy.

**Tests shown to catch their bug.** `tests/test_food_content_check.py` has 24 tests. Each of these failed with its behaviour removed and passed with it restored:

- A nutrition correction that also changes the amount and unit.
- The switch ignored.
- An unusable answer read as a rejection: 10 tests failed.
- An unusable answer not asked again: 15 tests failed.
- Foods checked one after another.
- The check shown the conversation.
- The check shown the food's date.
- A rejection accepted without a reason.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 1002 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. No contract or tool definition changed in this step.
- Not run: `npm run check`, because the frontend did not change, and no eval run.

**Left open**

- Whether the check judges nutrition values well is the model's work, and only the eval run in step 13 measures it.
- `test_a_value_the_user_stated_survives_a_rejection` scripts a correction that keeps the user's 1900 kcal. It proves the correction is shown the conversation, is told to keep stated values, and is not checked again. Whether a real model then keeps the value is for the eval's `stated-nutrition` cases.

### 2026-11-17 Step 6: recordFood uses the checks

**What changed**

- A new module, `checks.py`, runs the name and count check, the date check and the content check in that order (P1), each on the list the one before it leaves. `recordFood` saves the list they leave, and saves nothing until the last check has finished.
- The old verifier is removed: `verification.py`, `submitFoodVerdict`, `RunSettings.verify_food` and `tests/test_food_verification.py`.
- The tool result lists the saved foods under `recorded`. A record the checks removed is listed under `notRecorded` with its reason, beside a `note` that tells the chat model not to record it again. The old result told the chat model to tell the user why a food was refused; a removed record is now often a duplicate or a whole-meal record whose foods were saved, so the note no longer asks for that.
- The name and count check and the date check now run in every chat. `RunSettings.check_nutrition` switches only the content check (D3).
- The eval runner sets `check_nutrition` for its two modes and counts the records a call lists as not recorded. Its report still says "verification off and on"; step 7 rewrites the report.
- `docs/AGENT_OPERATIONS.md` has a rewritten "Food verification" section that describes the checks, and `docs/DEVELOPMENT.md` and `agent-python/README.md` describe `recordFood` as it now works. Step 11 still covers the switch, the protocol and the eval README.
- The fakes that pass every check take their answers from `tests/support/persistence_worker.py`. That file is the worker the Java integration tests start as a script, so it can import only `tyler_agent`, and keeping the answers there means one copy.

**Tests.** `tests/test_food_record.py` has 23 tests through the real agent loop, tool registry and `recordFood`, each real provider class against an in-memory server, and the production runtime:

- The checks run in order and nothing is saved before the last one. One chat has a whole-meal record replaced by its food, an invented date corrected and an implausible value rewritten.
- A food from a file the chat model read is saved (#100), and no check prompt holds the API key.
- A second `recordFood` call in the same model response is shown what the first saved (P3).
- With the nutrition check off, only the other two checks run.
- A provider failure during any check, or a required check that cannot be answered, saves nothing (#101).
- Cancelling during the name, date or content check, through the runtime, saves nothing.
- OpenAI, Claude and DeepSeek each carry all five tools.

**Tests shown to catch their bug.** Each of these failed with its behaviour removed and passed with it restored:

- The date check run before the name and count check: 10 tests failed.
- The foods the chat model gave saved, not the list the checks leave.
- Each food saved before the checks run: 13 tests failed.
- The removed records not reported.
- The checks not shown the run's earlier calls.
- A failed check skipped and the foods saved anyway: 10 tests failed.
- The content check left out: 10 tests failed.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 977 passed and 1 skipped (the owner-only file mode test, which Windows cannot run). The count is lower than after step 5 because the old verifier's 48 tests are gone and 23 were added.
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. `AgentPersistenceIntegrationTest` drives the real worker, which now runs every check on each food.
- Not run: `npm run check`, because the frontend did not change, and no eval run.

**Left open**

- The foods are still saved with one `food.insert` each. If Java rejects a later food, the earlier ones stay saved, until step 9 adds the one-transaction save (D9).
- A required check that cannot be answered fails the chat as a provider failure, which the user sees as "Internal server error". Step 8 adds a failure code of its own (D10).
- The eval report's wording and its verification rows describe the old verifier until step 7.

### 2026-11-17 Step 7: the eval report shows what the checks changed

**What changed**

- Each check now records a finding for everything it does: the check's tool, the food, the change and the reason. `FoodList.findings` replaces the list of removed foods from step 3. The changes are: removed, added, amount corrected, date corrected, nutrition corrected, and two for a nutrition check that could not be settled, when the check or its correction gave no usable answer.
- `recordFood`'s adapter is split into two steps, `record_foods`, which checks and saves, and `rendered`, which writes the tool result. The tool result is unchanged: it lists only the removed records under `notRecorded`.
- The eval runner stands in for `recordFood`'s handler in each chat. It calls those same two steps, keeps the findings, and returns what the real handler returns, so the chat sees the same result. This replaces counting refusals from the result text.
- The report has six new rows that count the changes, and a new table for each model, "What the checks changed", with one row for each finding and its reason. This answers #113's first design question: why a record was rejected is no longer a guess.
- The two modes are now named for what they switch: the nutrition check off and on. The name and count check and the date check run in both. The progress line says `nutrition-check=off` in place of `verification=off`.
- The eval README explains the new rows, and that reports made before the split are not directly comparable: in those, "off" meant no check at all.

**A defect found on the way.** A food with no name was shown as `\-` in the new table, because the harness's own placeholder was escaped like model text. It is now shown as `-`, and a test covers it.

**Moved to step 9.** `FakeJava` answering the new save operation was listed under this step. That operation does not exist until step 9, so it moved there.

**Tests shown to catch their bug.** Each of these failed with its behaviour removed and passed with it restored:

- The name and count check recording no finding: 7 tests failed.
- The date check recording no finding.
- A nutrition check without an answer leaving no finding: 10 tests failed.
- The tool result listing every finding as not recorded.
- The eval runner keeping no findings.
- The eval's stand-in giving the chat a different result from the real handler.
- The report without the list of what the checks changed.
- A food with no name shown as an escaped dash.
- The change counts including chats that failed.
- A model's reason put into the report as written, without escaping.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 980 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. No contract or tool definition changed in this step.
- Not run: `npm run check`, because the frontend did not change, and no eval run. A sample report was rendered from scripted chats and read.

**Left open**

- A check that changes nothing leaves no row, so the report does not show what a check was asked about a food it accepted.
- The report's rows and the new table are untested against a real model until the eval run in step 13.

### 2026-11-17 After the first eval run: stated values and missing dates

**The run.** The maintainer ran `--cheap-models` on #104's four categories with the checks as they stood after step 7: 23 cases, 3 repeats, 414 chats. It is a first look, not the run step 13 reports, and its report is not in the repository.

| Model | Chats that failed | Fully correct, switch off → on (%) | Missed items | Dates corrected, off / on | Foods saved with no date | Nutrition values corrected, on |
|---|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 0 | 98.6 → 97.1 | 0 | 16 / 16 | 0 | 9 |
| `claude-haiku-5-5` | 0 | 92.8 → 94.2 | 0 | 0 / 0 | 0 | 0 |
| `deepseek-flash` | 0 | 97.1 → 94.2 | 0 | 47 / 43 | 2 | 1 |

What it showed:

- **All three providers accept the new tool definitions.** No chat failed. This was untested until this run.
- **The date check corrects the dates the baselines found wrong.** No food was saved with a wrong date.
- **Two foods were saved with no date.** `deepseek-flash` left the date out, and its date check passed both. This led to D16.
- **`claude-haiku-5-5`'s checks ran and changed nothing.** It made 5.00 model calls for each chat with the switch off and 6.00 with it on, which is the two checks and then the third. Its records had no wrong date in this run or in any earlier report. Its content check accepted values the eval scores as wrong, such as corn at 140 kcal against 98.9.
- **The content check did not improve two of the three models.** Fully correct chats fell by one chat on `gpt-6-luna` and two on `deepseek-flash` with the switch on. What counts as done asks for the opposite, so step 13 has to look at this again after D15.

**What changed**

- **D15.** `verifyFoodNutrition` is shown the conversation and the run's earlier tool calls beside the one food. Its prompt says a value the user stated is right however unusual it looks, that a record which differs from a stated value is rejected, and that plausibility is judged only for values the user did not state. The food's date is still not shown.
- **D16.** `check_dates` adds every food with a missing or blank date to the foods the check named, with the reason "The record has no date." The correction must then give each of them a date, or the chat fails as D14 says. The check's own reason is kept for a food it named.

**Tests shown to catch their bug.** Each of these failed with its behaviour removed and passed with it restored:

- A food with no date left to the check's answer: 5 tests failed, one of them through the real agent loop.
- A blank date counted as a date.
- The fixed reason replacing the check's own reason.
- The content check not shown the conversation.
- The content check not shown the earlier calls.
- The content check not told that a stated value is right.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 986 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. The first run had one failure, in `AgentPersistenceIntegrationTest`: its "missing food" scenario sends a food with no fields, so D16 asked for a date correction, and the scripted worker had no answer for that request. `tests/support/persistence_worker.py` now answers it, and Java then rejects the food for its missing fields as before.
- Not run: `npm run check`, because the frontend did not change, and no new eval run.

**Left open**

- #113's description and PR #117's description still say the content check is not shown the chat.
- The nutrition-check-on results of the run above are out of date after D15, and its two foods with no date are fixed by D16. A new run is needed before step 13's comparison.
- The content check now sends the conversation and the tool results once for each food. With a large file and many foods that is the cost D15's option B accepted.

### 2026-11-18 The full run on the low-cost models

**The run.** The maintainer ran `--cheap-models` on every category with the checks as they stand after D15 and D16: 67 cases, 3 repeats, 1,206 chats, started 2026-11-18 02:27 UTC. All chats ran, and two failed. It holds both sets step 13 names, the 20 `many-items` cases and #104's four categories. It is not step 13's write-up, and its report is not in the repository. The report's estimates put the run at about $1.28.

| Model | Chats that failed | Fully correct, switch off → on (%) | Missed items, off / on | Chats with a date problem | Dates corrected, off / on | Nutrition values corrected, on | Model calls per chat, off / on | Median latency, off / on (s) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 0 | 94.5 → 94.5 | 4 / 4 | 0 | 98 / 78 | 40 | 4.70 / 7.02 | 6.8 / 8.6 |
| `claude-haiku-5-5` | 0 | 91.5 → 93.5 | 1 / 1 | 0 | 0 / 0 | 16 | 4.74 / 6.97 | 6.4 / 8.9 |
| `deepseek-flash` | 2 | 95.5 → 97.0 | 2 / 0 | 0 | 193 / 226 | 2 | 4.74 / 6.94 | 5.9 / 7.3 |

**Against the baselines, on the cases each baseline shares with this run.** Fully correct chats (%). The unit cases are 22: the baseline's scoop case was replaced and its Pizza Hut case removed after it ran.

| Model | `many-items`: baseline off | baseline on | this run off | this run on | Unit cases: baseline off | baseline on | this run off | this run on |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 80.0 | 58.3 | 91.7 | 95.0 | 71.2 | 92.4 | 97.0 | 93.9 |
| `claude-haiku-5-5` | 80.0 | 73.3 | 81.7 | 90.0 | 89.4 | 93.9 | 93.9 | 92.4 |
| `deepseek-flash` | 64.4 | 47.5 | 94.9 | 93.2 | 68.2 | 90.9 | 97.0 | 97.0 |

The baselines are the [many-items run](evals/many-items/2026-11-16-many-items.md) and the low-cost models of the [units run](evals/units/2026-11-17-units.md). In the baselines, "off" is no verification at all. In this run the name and date checks run in both columns.

**Against what counts as done**

- **Item 3, the cheap models improve: met.** With the switch on, every model beats its baseline verification-off result on both sets. The smallest margin is `claude-haiku-5-5` on the unit cases, 61 chats against 59 of 66.
- **Item 4, dates: met.** No chat of the 1,206 lists a wrong or missing date. The date check corrected 595 dates, all on `gpt-6-luna` and `deepseek-flash`. For 32 of `deepseek-flash`'s, the record had no date at all, which is D16 at work.
- **Item 4, missed items: met on `many-items`, not met elsewhere.**
  - `many-items`: no missed item on any model, off or on. The baselines had 5 and 28 (`claude-haiku-5-5` and `deepseek-flash`, verification on, many-items run).
  - Unit cases: one chat, `gpt-6-luna`'s `chick-fil-a-sandwich`, switch on.
  - The other categories: `some-almonds` in 10 chats over the three models, and `gpt-6-luna`'s `corrected-date` once.
  - In every one of these chats nothing at all was saved, and no check removed a record. So the chat model most likely never called `recordFood`, and the checks, which run inside it, never ran. The report does not say whether `recordFood` was called, so this is not confirmed. `some-almonds` was missed in the baselines too.
- **Item 5, better with the content check on: not met.** Over the whole run `gpt-6-luna` is level. On `many-items`, `deepseek-flash` is one chat lower with the switch on. On the 23 unit cases as run, `gpt-6-luna` is two chats lower, `claude-haiku-5-5` one, and `deepseek-flash` level.

**Read without `some-almonds`.** The maintainer set this case aside on 2026-11-18: "I snacked on some almonds this afternoon" can be read as general chat that calls for no `recordFood`. Without it the run has 66 cases, and two chats with a missed item remain, both on `gpt-6-luna`. The maintainer marked the run a temporary success.

The table gives fully correct chats (%) against each case's newest baseline: the units run for `many-items` and the 22 unit cases, and the [nutrition-sources run](evals/nutrition-sources/2026-11-16-nutrition-sources.md) for the other 23. The new scoop case has no baseline, so the comparison has 65 cases. The nutrition-sources run was scored before #104's unit rules, which accepted more units, so its figures are not lower than they would be now.

| Model | Baseline, no verification | Baseline, verification on | This run, switch off | This run, switch on | All 66 cases, switch on |
|---|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 75.9 | 89.7 | 95.9 | 95.9 | 96.0 (190 of 198) |
| `claude-haiku-5-5` | 90.3 | 91.8 | 91.8 | 93.8 | 93.9 (186 of 198) |
| `deepseek-flash` | 72.7 | 86.1 | 96.4 | 96.9 | 96.9 (190 of 196) |

**What the content check did**

- **`claude-haiku-5-5`: a real gain.** It corrected 16 foods in 9 chats, and 7 of those chats ended fully correct. Ten of the foods had 0 for every nutrition value.
- **`gpt-6-luna`: many corrections, no gain.** It rejected 40 foods in 36 chats. About half of its reasons end by saying the values are plausible, or name a difference of a tenth of a gram, which the prompt says is not a reason to reject. Several give "the user did not state the values" as the fault, which misreads D15's wording. 34 of the 36 chats were fully correct afterwards. One right value was made wrong: a pumpkin pie slice at 323 kcal, the reference value, was rewritten to 243.
- **`deepseek-flash`: two corrections.** One doubled the values of two rice cakes that were right. Its higher result with the switch on comes from chats that went wrong before any check ran with the switch off, not from the check.
- **Stated values (D15).** In `stated-nutrition`, all 36 chats with the switch on were fully correct, and no check rejected the 1900 kcal banana. With the switch off, `deepseek-flash` saved 105 kcal for it in 2 chats: the chat model ignored the stated value, and nothing checks that when the content check is off.

**Other findings**

- **`claude-haiku-5-5` wrote 0 for every nutrition value in 5 chats,** all with messages of two to eight foods: 3 of its 24 `several-items` chats and 2 of its 120 `many-items` chats. With the switch on, the content check corrected them in 3 chats. With it off, 2 chats saved them, 10 foods in all. No earlier report has this. The one thing the chat model sees differently is that `recordFood` now takes a list; that this is the cause is not confirmed.
- **The grande latte is still converted.** `deepseek-flash` wrote 16 fl oz in 4 of 6 chats, and the name check corrected 1.
- **No double save.** No chat saved a food nothing expected. The name check removed one record, on `gpt-6-luna`: a second `recordFood` call for a food an earlier call had saved.
- **No food was added, and no record held a whole meal.**
- **The two failed chats** are `deepseek-flash` calling a file tool the harness does not answer (`unexpected operation: file.read`). The run of 2026-11-16 has the same failure.

### 2026-11-18 The name and count check is switched off (D17)

**Why.** In the full run above the name and count check made one model request in every `recordFood` call and changed a record in 4 of 1,206 chats: it removed one record, had three amounts or units written again, and added no food. The maintainer's reading: the run proves what the check costs, and not that it does important work. [#118](https://github.com/phanmi/tyler_agent/issues/118) is to prove that with new classes of eval cases, or show that it cannot be proved.

**Why the proof is a separate issue.** The current cases rarely lead a chat model into the mistakes the check is for, so proving that it works needs complex cases designed for that. The maintainer decided that designing them is out of scope for #113. Until then the requirements that rest on the check are partially met: the check is implemented and tested, and it is switched on only after [#118](https://github.com/phanmi/tyler_agent/issues/118) proves it works.

**What changed**

- **A switch, off by default.** `RunSettings.check_names` is false unless code sets it. The runtime gives every run the default settings and the run payload carries none, so no run can switch the check on.
- **The check does nothing when it is off.** `check_names` returns the list it was given and makes no request. It keeps its place in `CHECKS`, so switching it on needs no other change.
- **A run now makes the date check and the content check.** That is one model request fewer for every `recordFood` call.
- **The eval harness runs with the check off, as the app does.** The report's three rows for it stay, and read 0. How the harness switches it on belongs to [#118](https://github.com/phanmi/tyler_agent/issues/118).
- **Tests.** The check's own tests, and the end-to-end tests of what it does, switch it on. The other end-to-end tests run a run's default settings. One eval-runner test needed a removed record, which the harness can no longer produce, and was removed; the one beside it now uses passing checks.
- **Docs.** `AGENT_OPERATIONS.md`, `DEVELOPMENT.md` and the eval README say which checks a run makes.
- **#113 and PR #117.** Their descriptions say that the check is implemented and switched off, why, and that [#118](https://github.com/phanmi/tyler_agent/issues/118) comes before it is switched on. They also say that the content check is shown the chat (D15) and that a food with no date is always corrected (D16).

**What a run loses while the check is off.** Going by the full run:

- A second `recordFood` call for a food an earlier call saved is saved again. This was one chat, on `gpt-6-luna`.
- An amount or unit that differs from what the user said is saved. The check corrected three, all on `deepseek-flash`, and passed three more of the same kind.
- A record that does not belong is saved, and a food the chat model left out stays out. The run had neither.

**Tests shown to catch their bug.** Each of these failed with its behaviour removed and passed with it restored:

- The check asking although it is switched off: 13 tests failed, among them the check's own test and one through the real agent loop.
- The switch being on by default: the same 13 tests failed.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 987 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped. Its integration tests start the worker with the check off, as a run does.
- Not run: `npm run check`, because the frontend did not change, and no eval run.

**Left open**

- The full run's figures include the name and count check. A run with it off has not been made.
- PR #117's review finding about two `recordFood` calls in one response rested on this check seeing the first call's result.

### 2026-11-18 The full run is written up

**What was added.** The [verification overhaul eval](evals/verification-overhaul/2026-11-18-verification-overhaul.md), with the run's generated report beside it. It is the write-up of the full run above, which was made before the name and count check was switched off.

**What it holds**

- **The improvement over the baselines.** Fully correct chats on the 65 cases that have a baseline, and on each set: `many-items`, the 22 unit cases, and the other 23.
- **What went wrong in each chat, baseline against this run:** missing foods, dates, amounts and units, nutrition values, foods the case does not expect, and failed chats.
- **What each check did,** with the content check's reasons counted for `gpt-6-luna`: 14 of its 40 note that the user did not state the values.
- **Cost and time** against the run of 2026-11-16, which has 61 of the 67 cases. With the nutrition check on, a chat costs about what it did with the old verifier on, and the 95th percentile of reply time is about half.
- **The run against what counts as done.**
- **The three decisions made from the run, each with its reason and evidence:** `some-almonds` set aside, the run marked a temporary success, and the name and count check switched off (D17).

**One figure differs from the entry above.** That entry says the name and count check passed three records of the kind it is meant to correct. The write-up counts four: `deepseek-flash`'s three lattes, and a Snickers bar that `claude-haiku-5-5` saved with the unit "bar (2 oz)".

**Left open**

- A run with the name and count check off has not been made, so the write-up does not measure the checks as they now stand.
- The report does not split reply time by category. So the time a multi-food message takes, which #113 asks for before and after, is not measured directly.
- PR #117's description still says the report is not in the repository.

### 2026-11-18 Review findings F1 to F10 on PR #117

**What this is.** The review of the branch found ten things, F1 to F10. Each was posted on PR #117 with its options, and the maintainer answered each in its thread. This entry applies those answers.

| Finding | Answer | What changed |
|---|---|---|
| F1. A correction with missing fields is accepted | B | `corrected_record` rejects a `correctFood` answer with a field missing or null, or with a negative number (D18). The question is then asked again, as D10 and D14 say. |
| F2. A corrected date that is not a date is accepted | B | `corrected_dates` accepts only a real day written as `YYYY-MM-DD` (D19). |
| F3. With two `recordFood` calls in one response, the first call's check adds the second call's foods | A | No code change. In the full run no chat saved a food its case did not expect, and the name and count check added no food. The case cannot happen while that check is off. |
| F4. Nothing limits how many check requests go out at once | A | `together` asks at most 8 questions at the same time, `MAX_QUESTIONS_TOGETHER` (D20). It now takes the function that asks and the items to ask about, so a question beyond the limit is not built until its turn. |
| F5. `correctFood`'s description tells the model to use a `foods` list | A | `correctFood` is given `recordFood`'s description from "When the user gives an amount" on. |
| F6. The chat model is not told to tell the user about a left-out record | A | The `note` in the tool result says to tell the user which records were not recorded and why (D21). |
| F7. The date check and the content check could run at the same time | A | No code change (D22). |
| F8. `corrected_dates` repeats the loop in `flagged_foods` | A | `numbered_texts` in `asking.py` reads both lists, and both schemas share `FOOD_NUMBER`. |
| F9. The same evidence text is in four prompts | A | `Evidence.text` writes the block, and each prompt has one `{evidence}` placeholder. The correction's prompt now also says that the last message is the user's latest. |
| F10. `FoodCall.model_calls` is counted but nothing reads it | A | `FoodCall` is removed. `RecordedFood` and `ChatResult` keep only the findings. |

**Two things about F1 and F2**

- **They repeat rules Java has.** PY-04 says Python does not tighten Java's date or number rules. These two apply only to the model's answers to the checks' own tools, and Java still applies its rules to every food it saves. `PYTHON_AGENT_STANDARDS.md` is unchanged.
- **For F1, every field of the record is required.** That includes a field the asking check does not copy, such as the date in a nutrition correction. It is what the tool's schema requires, and what OpenAI and Claude already enforce.

**Tests shown to catch their bug.** Each fix was removed in turn. The tests named here failed, and passed with the fix restored.

- F1, a missing or null field: 2 cases of the content check's unusable-correction test.
- F1, a negative number: 1 case of the same test.
- F2: 4 cases of the date check's unusable-correction test. With only the shape check removed, the `20261110` case failed.
- F4: the new test that a list of 11 foods has at most 8 checks waiting for an answer at once.
- F5: the test of the rules the correction tool is given.
- F6 and F9: the end-to-end test of every check, which now reads the note in the tool result and the prompt of each request.

F8 and F10 change no behaviour, and the existing tests cover them. One eval-runner test asserted the entry for a rejected call, which is removed; it now asserts that the chat goes on and nothing is saved.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 995 passed and 1 skipped (the owner-only file mode test, which Windows cannot run).
- Java: `mvn test` with 887 tests, 0 failures, 0 errors and 5 skipped.
- Not run: `npm run check`, because the frontend did not change, and no eval run.

**Left open**

- No eval has run with these changes. Two of them change what a correction request sends: `correctFood`'s description is shorter (F5), and its prompt says that the last message is the user's latest (F9).
- The newest automated review of PR #117 has one finding without a decision: the content check is on in every run, and the full run shows it making some correct records wrong.

### 2026-11-20 Step 8: protocol version 3, the switch in `run` and the failure code

**What this is.** Step 8 as D25 leaves it: the new protocol version, the switch, and the failure code. The operation that saves a list of foods is step 9's.

**What changed**

- **The contract is version 3 (D8).** `contracts/agent/v2/` moved to `v3/` with `git mv` and was edited in place. The schema, both validators and every fixture say 3.
- **A version 2 peer fails with `UNSUPPORTED_VERSION`.** The message case `invalid_version`, the framing case `unsupported_version` and the fake workers of both test suites now send version 2 as the retired version.
- **`run` has a required boolean, `checkNutrition` (P10).** Five new message cases: the switch off is accepted, and a missing value, text, null and a number are each rejected. Every other `run` case names the field, so it is still valid or invalid for its own reason.
- **Python reads it.** `agent.run_settings` builds `RunSettings` from the payload, and the runtime uses that in place of the defaults. The eval runner puts the switch in its payload and reads it back the same way, so a chat in the eval gets its settings as a chat in the app does.
- **Java sends `true` in every run.** `PythonAgentService` has no saved setting to read until step 9.
- **A check that cannot be answered fails the run with a code of its own (D10, D24).** The schema's `provider` category has a second code, `MODEL_ANSWER_UNUSABLE`, with one new message case. `must_ask` raises it where it raised `PROVIDER_FAILURE`. A request that itself fails is still `PROVIDER_FAILURE`.
- **Java does not treat the new code differently yet.** It has no mapping of its own, so the user still sees "Internal server error" until step 9.
- **Docs.** `AGENT_PROTOCOL.md` describes version 3, D8's rule for moving the contract, and the new code. `AGENT_OPERATIONS.md` names the code a failed check ends with. `AGENT_SESSION.md`, `BACKEND_STANDARDS.md`, `DEVELOPMENT.md`, the Python README and the review rules name the new version or path. `AGENT_SESSION.md` still said version 1.

**Tests shown to catch their bug.** Each change was removed in turn. The tests named failed, and passed again with it restored.

- The schema not requiring `checkNutrition`: the missing-value case failed in Python and in both Java fixture tests.
- The schema accepting any type for it: the text, null and number cases failed in Python and in both Java fixture tests.
- The runtime ignoring the payload: `test_the_run_payload_switches_the_nutrition_check` failed, once with the switch forced on and once with it forced off.
- Java not sending the field: `PythonAgentServiceTest` failed.
- The eval runner not sending the field: 12 eval-runner tests failed.
- The schema without `MODEL_ANSWER_UNUSABLE`: the new message case failed in Python and in both Java fixture tests.
- `must_ask` raising `PROVIDER_FAILURE` again: 28 tests of the name and count check, the date check and `recordFood` failed. They are the five existing tests of a required answer that stays unusable, which now assert the new code.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 1003 passed and 1 skipped.
- Java: `mvn test` with 899 tests, 0 failures, 0 errors and 5 skipped. Before the failure code was added, `mvn clean test` ran on the moved contract with 897 tests and the same result.
- Not run: `npm run check`, because the frontend did not change. The Windows bundle was not rebuilt, although `worker.spec` now takes the schema from `v3/`.

**Left open**

- **Version 3 changes once more in step 9,** when `food.insert_all` replaces `food.insert` (D23, D25). So no release should carry step 8 without step 9.
- **The new code has no message for the user yet.** Step 9 adds Java's mapping for it.
- No eval has run with these changes. None of them changes what a model is sent.

### 2026-11-21 Step 9: the saved switch, the one-transaction save and the failure's message

**What this is.** Step 9 as D23 to D28 leave it. It has three parts: Java saves the switch and sends it, a message's foods are saved in one transaction, and the user is told when a required check could not be answered.

**The switch (D7, D27)**

- **Its own model, service, controller and file.** `AgentSettings` holds `checkNutrition`. `AgentSettingsService` keeps it in `agent-settings.json`, and `AgentSettingsController` serves `GET` and `PUT /api/agent/settings`. The property `agent.settings-file-path` names the file.
- **On when nothing was saved.** A missing file means on. So does a file that does not mention the switch.
- **A missing or non-boolean value gets HTTP 400 naming the field.** The text `"true"` and the number `1` are rejected too, and nothing is saved.
- **An unreadable or invalid file is an error.** The settings request and the chat fail with HTTP 500 until the switch is saved again, which rewrites the file.
- **The model's file tools refuse the file.** The service implements `IApplicationOwnedFiles`.
- **A chat reads the saved value when it leaves the queue.** `PythonAgentService` puts it in the `run` payload, so a change applies to the next chat.

**The one-transaction save (D9, D23, D28)**

- **The contract.** `food.insert_all` takes `{foods: [food, ...]}` with at least one food and returns `{foods: [{id, food}, ...]}` in the same order. `food.insert` is no longer an operation. Twenty-nine message cases changed shape, and four new cases reject an empty list, the old one-food input, the old operation name, and an empty result.
- **Java.** `FoodInsertAllHandler` replaces `FoodInsertHandler`. `IFoodService.insertFoods` replaces `insertFood`: it validates every food, then inserts them in one transaction.
- **The transaction is real.** `FoodDatabaseConfig` gives the food DAO and a transaction manager the same data source, as `WorkoutDatabaseConfig` does for workouts. The DAO now takes that `JdbcTemplate`.
- **Python.** `recordFood` sends the foods its checks leave in one request. When the checks leave no food it sends none. The eval's `FakeJava` and the test fakes answer the new operation.

**The failure's message (D10, D24, D26)**

- **HTTP 502 with its own text.** `ProtocolFailures` maps `MODEL_ANSWER_UNUSABLE` to `ModelAnswerUnusableException`, and the new `ProviderExceptionHandler` answers "The model could not complete a required check. Please try again".
- **The sessions agree.** The Java and the Python session report 502 for this code, and 500 for the rest of its category.

**Choices of mine, not decided by the maintainer**

- A settings file that does not mention the switch means on. It is how `llm-settings.json` treats a provider it does not mention, and it lets #82 add a setting without making older files invalid.
- In the request body, JSON `null` counts as a missing value.
- The list has no upper limit in the schema. The frame limit of the protocol still bounds it.
- Inside Python a food keeps its `{"food": ...}` wrapper, so the checks did not change. The adapter unwraps it for the request.
- The food database got a configuration class of its own rather than a transaction inside the DAO, because `BACKEND_STANDARDS.md` asks for a transaction manager on the DAO's data source.

**Docs.** `AGENT_PROTOCOL.md`, `AGENT_OPERATIONS.md`, `AGENT_SESSION.md`, `BACKEND_STANDARDS.md`, `FILE_SANDBOX.md` and `DEVELOPMENT.md` describe the operation, the settings endpoint, the file, the property and the two new messages a user can see.

**Tests shown to catch their bug.** Each change was removed in turn. The tests named failed, and passed again with it restored.

- The schema allowing an empty list, the old one-food input, the old operation name or an empty result: the matching new message case failed, in Python and in both Java fixture tests.
- `recordFood` sending an empty list: `test_a_call_whose_only_record_is_removed_asks_java_to_save_nothing` failed.
- `recordFood` sending one request for each food: the test of the checks' order and three tool tests failed.
- Java inserting outside a transaction: the rollback test at the bridge and the one through the real worker failed.
- Java validating each food just before its insert: `FoodServiceTest.invalidInputsNeverAccessStorage` failed.
- Both together, which is the old one-at-a-time save: the two rollback tests failed, and so did the `second_food_negative` case through the real worker.
- The handler calling the service once for each food: `FoodInsertAllHandlerTest` failed.
- `MODEL_ANSWER_UNUSABLE` without its mapping: `ProtocolFailuresTest` and the `unanswered_check` case through the real worker failed.
- The advice answering 500: `ProviderExceptionHandlerTest` and `ProtocolFailuresTest` failed.
- A session reporting 500 for the code: `ProtocolSessionTest` failed for Java, and `test_session.py` for Python.
- The run always carrying `true`: `PythonAgentServiceTest` and the switch test through the real worker failed.
- A missing value read as on, and text or a number read as a boolean: `AgentSettingsControllerTest` failed for each.
- An invalid file read as the default: `AgentSettingsServiceTest` and `AgentSettingsControllerTest` failed.
- The settings service declaring no file: `ProtectedWorkspaceFilesTest` failed, and through the real worker the model could read the file.
- The list save left out of the write operations: 10 sequence cases failed in each language.
- The eval's `FakeJava` not answering the operation: 5 eval-runner tests failed.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 1009 passed and 1 skipped.
- Java: `mvn clean test` with 952 tests, 0 failures, 0 errors and 5 skipped.
- Not run: `npm run check`, because the frontend did not change. The Windows bundle was not rebuilt. No eval ran; these changes do not alter what a model is sent.

**Left open**

- **The switch has no control in Settings yet.** Until step 10 it can be changed only through the endpoint. Step 10 added it.
- **The README's Settings section** waits for step 11, with the frontend. Step 11 wrote it.
- **The names of the write operations are listed in four places:** `ProtocolSession`, `PythonWorker` and `WorkerMessages` in Java, and `session.py` in Python. Replacing `food.insert` meant editing all four. They were listed that way before this step. [#120](https://github.com/phanmi/tyler_agent/issues/120) is to give them one place.

### 2026-11-21 Step 10: the switch in Settings

**What this is.** The frontend for the switch that step 9 stores: Settings shows it, changing it saves it, and a failed save is shown.

**What changed**

- **A card on the Settings page, "Food recording".** It holds one switch, "Check nutrition values" (P10), a native checkbox with the switch role. It is on when nothing was saved, because that is what the backend answers.
- **Its description says what the check costs:** one more model call for each food, and another when the values need correcting. It also says that the date of each food is checked either way. The description is read out with the switch.
- **Changing it saves at once.** While the save runs, the switch shows the choice and is locked. Then it shows what the backend saved and a line that says so.
- **A failed save shows the backend's message,** such as the one that names the field, and the switch returns to the saved value.
- **A failed load shows the error with a retry,** and no switch that could be taken for the saved value.
- **An invalid `agent-settings.json` gets a reset,** which saves the default and so rewrites the file (D27). Without it the user could only delete the file by hand.
- **The API client** has `loadAgentSettings`, `saveAgentSettings` and `isInvalidAgentSettingsFile`, and checks that the response holds a boolean.
- **Docs.** `DEVELOPMENT.md` says where the switch is and how to repair the file from Settings.

**Choices of mine, not decided by the maintainer**

- The card's title, "Food recording", and the wording of the description and of the two confirmation lines.
- The switch is drawn as a checkbox, like the other controls on the page, and announced as a switch.
- The reset for an invalid file, and its wording.
- The card shares its look with the provider card. The shared CSS rules name both prefixes, `llm-` and `agent-`, so neither card uses the other's class names.

**Tests shown to catch their bug.** Each of 17 behaviours was broken in turn. The tests named for it failed, and passed again with it restored.

- The API client: reading any response as settings, saving with POST, not passing the abort signal, and taking any error with the text for the invalid file.
- The Settings page without the card.
- The switch: no description tied to it, a second change sent while one is pending, success shown before the backend answers, a failed save with no message, and always showing on.
- The settings hook: showing what was asked for in place of what the backend saved, showing the default after a failed load or before the load finishes, and not aborting the load when the page is left.
- The card: a reset that loads again in place of saving, only a retry for an invalid file, and saving the opposite value.

**Checked by eye.** The real Settings page was rendered against a stand-in backend and captured: the switch on, off with its confirmation, a failed save, the invalid-file reset, and keyboard focus. Sizes: 1200 by 800 at 100% and at 200% zoom, and 820 by 600. The card reflows and nothing is cut off. It was not run against the real backend or in the packaged app.

**Checks**

- Frontend: `npm run check` passes: Prettier, ESLint with no warnings, the type check, 118 UI tests in 7 files, 23 startup tests, 3 version tests, and the production build.
- Not run again: the Python and Java checks, because this step changed only the frontend and two documents.

**Left open**

- **Two shared colours are below the 4.5 to 1 contrast that `FRONTEND_STANDARDS.md` asks for small text:** `--success` is 3.41 to 1 on a white card and `--danger` is 4.38 to 1. The new card uses them as the provider card and the profile form already do. Changing the tokens would change every page.
- **The README's Settings section** waits for step 11. Step 11 wrote it.

### 2026-11-21 Step 11: docs

**What this is.** The docs step 11 names, read against what steps 8 to 10 built. Most were updated in the step that changed the behaviour. This entry lists what was still out of date or missing.

**What changed**

- **`README.md`.** The paragraph on what Tyler checks before saving still described the old single check: the food, its amount, a food refused after failing twice, and up to three extra requests for each food. It now describes the date check, the nutrition check, the save of a message's foods together, and what the checks cost. The Settings section describes **Food recording → Check nutrition values**.
- **`AGENT_OPERATIONS.md`.** The handler list named `FoodInsertHandler`. The persistence section said food is saved through the path HTTP uses, and HTTP has no insert. The test inventory lists the new tests, and the switches paragraph names the control in Settings.
- **What each check sees** was compared with the prompts in the code: the table in `AGENT_OPERATIONS.md` matched and did not change.
- **`DEVELOPMENT.md`.** The switch is part of what Java reads when a chat leaves the queue. The `recordFood` row, the paragraph on the databases, the repository layout and the styles paragraph name what steps 9 and 10 added.
- **The eval README.** It says that its two modes are the two positions of the switch in Settings. It lists the failure names a run shows, with the new `provider/MODEL_ANSWER_UNUSABLE`. It says that the stand-in for Java keeps the other foods of a call when one would be rejected.
- **`AGENT_PROTOCOL.md` and `BACKEND_STANDARDS.md`** were updated in steps 8 and 9 and needed nothing more.

**Checks**

- The targets of the new links exist. CI has no link check.
- `pytest tests` with 1009 passed and 1 skipped: one test reads the eval README.
- Not run: the Java and frontend checks, because this step changed only documents.

**Left open**

- **The README says a message's foods are recorded in one step.** A chat model that spreads them over several `recordFood` calls has each call saved on its own, as `AGENT_OPERATIONS.md` says under the limits of the checks.
- **`PYTHON_AGENT_STANDARDS.md`** has no sentence for the exception to PY-04 that D18 and D19 made. Whether it gets one was asked in the review of PR #117 and is not decided.

### 2026-11-21 Step 12: checks and code review

**Checks on the whole change**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 1009 passed and 1 skipped.
- Java: `mvn clean test` with 952 tests, 0 failures, 0 errors and 5 skipped.
- Frontend: `npm run check` passes: Prettier, ESLint with no warnings, the type check, 118 UI tests, 23 startup tests, 3 version tests and the production build.
- Each new test was shown to catch its bug in the step that added it; the entries for steps 8, 9 and 10 list them. After those runs, only documents changed.

**`/code-review`** ran on the whole change, at the effort level `high`. It reported seven findings. Each was checked against the code.

| | Finding | How it can happen | Decision |
|---|---|---|---|
| R1 | A database failure while the food transaction opens or commits is reported as a worker failure. `FoodService.insertFoods` lets Spring's `TransactionException` out, and `WorkerMessages` maps anything it does not know to `WORKER_UNAVAILABLE`. | The workspace folder is removed while the app runs, or SQLite is busy at commit. Chat then shows "Internal server error". Before step 9 the same failure was a `SQL_WRITE`, shown as "Database operation failed". `WorkoutPlanService` has had the same gap since before #113. | A. `insertFoods` turns a `TransactionException` into `SQLPersistentException` and keeps the cause. [#119](https://github.com/phanmi/tyler_agent/issues/119) holds the workout plan. |
| R2 | The names of the write operations are listed in four places: `ProtocolSession`, `PythonWorker`, `WorkerMessages` and `session.py`. | A later rename or a new write operation misses one copy. If it is `WorkerMessages`, a failed write is reported as having written nothing. The lists were there before #113; step 9 edited all four. | B. No code change. [#120](https://github.com/phanmi/tyler_agent/issues/120) is to make the schema the one place both languages read the list from. |
| R3 | The line that confirms a change of the switch states the value that was asked for, not the value the backend saved. | The backend answers with another value than it was asked to save. Today it always answers with what it saved, so this cannot happen yet. | A. The line states what the backend saved, and the test of that case asserts it. |
| R4 | The README says a message's foods are saved all or none and that the message can be sent again. The guarantee is for one `recordFood` call. | A model spreads a message's foods over two calls. The first is saved, the second fails, the user sends the message again, and the first call's foods are saved twice. | A. The README says "normally in one step" and tells the user to look at the Calendar before sending the message again. The troubleshooting row in `DEVELOPMENT.md` says the same. |
| R5 | The agent settings code repeats the provider settings code: the file handling of `LlmSettingsService`, the load-error and reset block of `ProviderSettingsPanel`, and the load effect of `useLlmSettings`. | A later fix to one copy leaves the other with the old behaviour. | A. `SandboxJsonFile` holds the file handling of both settings services. `useLoadedSettings` holds the load of both hooks, and `SettingsLoadError` the block both cards show after a failed load. |
| R6 | `MODEL_ANSWER_UNUSABLE` and its status 502 are written inline in `ProtocolSession`, and the code again in `ProtocolFailures`. | A typo or a rename in one of the two makes the session report 500 while the response is 502. | A. `ProtocolFailures.MODEL_ANSWER_UNUSABLE` names the code once. `ProtocolSession` has the two status tables the Python session has. |
| R7 | The class comment of `ProtectedWorkspaceFiles` lists the protected files without the agent settings file. | A reader takes `agent-settings.json` for a file the model's file tools can reach. The code refuses it. | A. The comment points to `IApplicationOwnedFiles` and lists no file. |

**The decisions.** The maintainer answered all seven on 2026-11-21: R1 A, R2 B, R3 A, R4 A, R5 A, R6 A and R7 A. The table says what each one changed.

**R5 changed code #113 had not touched:** `LlmSettingsService`, `ProviderSettingsPanel` and `useLlmSettings`. Their behaviour is the same, and their existing tests pass unchanged. `useLlmSettings` no longer keeps a flag for whether the page is still open, because React ignores a state change after that.

**Tests shown to catch their bug.** Each was removed or broken in turn; the tests named failed, and passed again once restored.

- `insertFoods` not translating a transaction failure: the two new `FoodServiceTest` cases failed, one for a transaction that cannot open and one for a commit that fails.
- `SandboxJsonFile` reading a missing file, passing content that is not an object to the parser, not translating a parser's rejection or a rejected path, and writing on one line: its own tests failed, and with them the tests of both settings services.
- The session without a status of its own for the code: `ProtocolSessionTest` failed.
- The confirmation line stating what was asked for: the test of a backend that answers another value failed.
- The shared hook showing a failed load as loaded, or not aborting a load; the shared block without the reset, or hiding a failed reset: the tests of both cards failed.

**Checks after these changes**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 1009 passed and 1 skipped.
- Java: `mvn clean test` with 964 tests, 0 failures, 0 errors and 5 skipped.
- Frontend: `npm run check` passes, with 118 UI tests, 23 startup tests, 3 version tests and the build.
- Not done: the review was not run again on the changes it led to.

**Left open**

- Nothing from this step. [#119](https://github.com/phanmi/tyler_agent/issues/119) and [#120](https://github.com/phanmi/tyler_agent/issues/120) hold what was moved out of #113.

### 2026-11-21 The content check is forced off (D29, D30)

**The run.** The maintainer ran `--cheap-models --category many-items` on the code of steps 8 to 12: 20 cases, 3 repeats, the content check off and on, 360 chats. All ran and none failed. The date check ran in both modes and the name and count check in neither. The generated report is `agent-python/evals/results/20261121-182459.md`, which git ignores.

| Model | Fully correct chats, check off → on (%) | Wrong saves, off → on | Model requests per chat, off → on | Cost per chat, on ÷ off | Median reply (s), off → on |
|---|--:|--:|--:|--:|--:|
| `gpt-6-luna` | 88.3 → 88.3 | 7 → 7 | 4.00 → 9.27 | 1.6 | 8.3 → 11.0 |
| `claude-haiku-5-5` | 78.3 → 85.0 | 19 → 11 | 4.00 → 8.92 | 2.0 | 8.5 → 12.2 |
| `deepseek-flash` | 86.7 → 81.7 | 8 → 12 | 4.00 → 8.93 | 1.7 | 5.7 → 6.9 |

- **No food was missed, and no saved date was wrong,** in either mode on any model. Every wrong save is a nutrition value outside the eval's tolerance.
- **With the check off,** 281, 269 and 280 of each model's 288 foods had every nutrition value within tolerance, and the median calorie error was 0.2%, 0.4% and 0.3%.
- **With the check on,** it had 43 foods written again. 6 of those fixed a value outside tolerance, all on `claude-haiku-5-5`. 10 made a correct food wrong: 5, 1 and 4. 20 foods with a value outside tolerance were saved as the chat model wrote them.
- **Against the many-foods baseline of 2026-11-16** with no verification, which had 80.0%, 80.0% and 64.4% fully correct: this run with the check off has 88.3%, 78.3% and 86.7%. `claude-haiku-5-5` is one chat below its baseline.
- **The time a multi-food message takes.** In the baseline the median chat took 7.7, 7.9 and 5.5 s with no verification and 16.4, 29.3 and 15.7 s with the old verifier on. In this run it took 8.3, 8.5 and 5.7 s with the content check off.

The counts of fixed, broken and unchanged foods were read from the report's two tables: a wrong save in a chat with the check on counts as made by the check when the check corrected that food in that chat, and the value its reason quotes was within tolerance before.

**D29.** The maintainer decided from this run that the content check is forced off and not deleted, and that [#113](https://github.com/phanmi/tyler_agent/issues/113) records it with the run as the reason. The section "Content check: forced off, and kept in the code" was added to #113's description on 2026-11-21.

**D30, option A. What was removed**

- **Frontend:** the Food recording card of Settings: `AgentSettingsPanel`, `NutritionCheckSwitch`, `useAgentSettings`, their tests, the agent settings calls in `api.ts` and their tests, and the card's style rules.
- **Java:** `AgentSettings`, `AgentSettingsService`, `AgentSettingsController`, their interfaces and tests, the property `agent.settings-file-path`, and the tests of `agent-settings.json` being refused to the model's file tools.
- **`PythonAgentService`** no longer reads a saved switch. It sends `checkNutrition: false` in every `run`, from the named constant `CHECK_NUTRITION`.

**What stays**

- **The check.** `content_check.py`, the food correction and their tests are unchanged. `RunSettings.check_nutrition` still switches the check, and the eval harness still runs every case with it off and on.
- **Protocol version 3** is unchanged: `run.checkNutrition` is still required and must be a JSON boolean.
- **The rest of steps 8 and 9:** `food.insert_all` and the one-transaction save, and the failure code `MODEL_ANSWER_UNUSABLE` with its HTTP 502. The date check still raises that failure.

**Tests**

- `PythonAgentServiceTest.everyChatIsSentWithTheNutritionCheckOff` replaces the two tests of the saved switch. It asserts the `run` payload carries the JSON boolean `false`.
- `AgentPersistenceIntegrationTest.aChatOfTheAppMakesNoNutritionCheckAndStillSavesItsFood` replaces the test of the switch saved over HTTP. Through the real Python worker, a chat that records a food asks for no nutrition check and saves the food.
- **Shown to catch their bug.** With `CHECK_NUTRITION` set to `true`, both tests failed; with the `checkNutrition` line removed from the payload, the first failed. Both passed again once restored.

**Docs.** `README.md`, `AGENT_OPERATIONS.md`, `AGENT_PROTOCOL.md`, `DEVELOPMENT.md`, `BACKEND_STANDARDS.md`, `FILE_SANDBOX.md` and the eval README no longer describe a switch. They say the content check is off in every chat of the app, and `AGENT_OPERATIONS.md` gives the reason.

**Checks**

- Python: `ruff format --check` and `ruff check` clean, `mypy` clean for `linux` and `win32`, and `pytest tests` with 1009 passed and 1 skipped.
- Java: `mvn clean test` with 929 tests, 0 failures, 0 errors and 5 skipped.
- Frontend: `npm run check` passes, with 96 UI tests, 23 startup tests, 3 version tests and the build.

**What R5 extracted, removed.** R5 gave the two settings cards shared code. With one card left, the maintainer decided on 2026-11-21 to remove it.

- **Deleted:** `SandboxJsonFile` and its test class, `useLoadedSettings` and `SettingsLoadError`.
- **As on `main` again:** `LlmSettingsService`, `ProviderSettingsPanel`, `useLlmSettings` and `settings.css`. Their tests were not changed by R5 and pass.
- **No frontend file differs from `main`** after this.
- **Checks after the removal:** `mvn clean test` with 919 tests, 0 failures, 0 errors and 5 skipped, and `npm run check` with 96 UI tests, 23 startup tests, 3 version tests and the build. No Python file changed, so the Python checks were not run again.

**#113 reworded.** The maintainer approved the wording on 2026-11-21 and it was posted the same day:

- The open point of the content check's section now says how the check is forced off.
- The scope section for #98 is titled "built, then removed".
- Two criteria replace the five about the switch, which #113 lists in one line as superseded.
- The docs criterion asks for protocol version 3 and for the docs to say the nutrition values are not checked a second time.
- The line on the `FRONTEND_STANDARDS.md` sections that apply says none do, because no frontend file changes.

**Left open**

- The run is not written up under `docs/evals/`, and #104's four categories were not run with the name and count check off.

### 2026-11-21 Second code review, and the PR (step 14)

`/code-review` ran at level `high` on the branch after D29, D30 and the removal of what R5 extracted. It found no blocking bug, and four findings. Each was checked against the code and is real.

The maintainer asked for the PR to be opened with the findings as comments, and decided them there on 2026-11-21.

| | Finding | Where | Proposed | Decision |
|---|---|---|---|---|
| R8 | The HTTP 502 text ends with "Please try again", whatever the run saved before it failed. When a chat's first `recordFood` call saved and its second call failed, sending the message again saves the first call's foods twice. Every other failure has the same gap, and it was there before this branch. | `ProtocolFailures.java` | A follow-up issue that covers every failure after a save | B. Fixed in this PR for this code, because it blocks the release. |
| R9 | The contract still describes a saved setting: the `$comment` of `run.checkNutrition`, and the description of the fixture `invalid_null_check_nutrition`. | `contracts/agent/v3/` | Reword both | A |
| R10 | The "Food validation" row of the current-state table in `AGENT_MIGRATION.md` names `food.insert`. | `docs/AGENT_MIGRATION.md` | Name `food.insert_all` and `IFoodService.insertFoods` | A |
| R11 | Python writes the code `MODEL_ANSWER_UNUSABLE` as text in two modules, and the status 500 that the same lookup falls back to has no name. Java has a constant for each. | `failures.py`, `session.py` | One constant for the code, and a named fallback status | A, to avoid duplicate code |

A second review posted on the PR reported R8, R9 and R10 again, and no other finding.

### 2026-11-21 The second review's decisions applied

**What changed**

- **R8.** For `MODEL_ANSWER_UNUSABLE`, the text goes by what the run saved before it failed.
  - Nothing (`none`): "The model could not complete a required check. Please try again", as before.
  - Something, or a write still open (`committed`, `unknown`): "The model could not complete a required check. Some foods from this message may already be saved. Check the Calendar before sending it again".
  - `ProtocolFailures.translate` takes the outcome as a parameter. `PythonWorker` passes the one in the session's decision on the `failed` message, which is Java's own ledger. The `writeOutcome` in the worker's failure is not read, because `AGENT_SESSION.md` calls it a claim and the ledger authoritative. For this failure the two hold the same value.
  - The other failures keep the gap, as option B says.
- **R9.** Both texts are reworded as proposed. What the schema accepts did not change.
- **R10.** The row names `food.insert_all` with every food of the call, and `IFoodService.insertFoods`.
- **R11.** `failures.py` has the constant `MODEL_ANSWER_UNUSABLE`, which `model_answer_unusable()` and the session's `STATUS_BY_CODE` use. `session.py` names the fallback `OTHER_FAILURE_STATUS`.

**Tests for R8**

- Through the real worker, the new scenario `saved_then_unanswered_check` makes two `recordFood` calls and answers only the first call's date check. The chat answers 502 with the new text, one food stays saved, and history is empty.
- `ProtocolFailuresTest` covers `unknown`, with a failure that itself claims `none`.
- With the text chosen without the outcome, both tests failed. With the fix back, both passed.
- R11 has no new test, because the fixture test `failed_model_answer_unusable` covers the lookup.

**Docs**

`AGENT_OPERATIONS.md`, `AGENT_PROTOCOL.md`, `AGENT_SESSION.md`, `DEVELOPMENT.md`, the README and the eval README give both texts, or say which one the user sees.

**Checks**

- Python: `ruff format --check`, `ruff check`, `mypy` for Linux and Windows, and `pytest tests` with 1009 passed and 1 skipped.
- Java: `mvn clean test`, with 921 tests, 0 failures, 0 errors and 5 skipped. That is the 919 from before and the two new tests.
- Frontend: not run, because no frontend file changed.

**Left open**

- The other failures still answer "Please try again later" after a save. No issue is opened for them.
- A user who sends the message again in spite of the warning still saves the first call's foods twice. #113's criterion about resending is met only as far as the app no longer asks for it.

### 2026-11-22 Step 13 finished, and #113 closed out

PR #121 was merged on 2026-11-22. The maintainer then asked for the rest to be cleaned up, a PR raised, and #113 closed. This entry is that PR.

**The decisions of 2026-11-22**

- **The resending criterion: option A.** #113's criterion is reworded to say what R8 built: after an earlier `recordFood` call of the chat saved, the app does not ask for a resend. Option B, making a resend unable to duplicate, needs a design and is not part of #113.
- **No new run of #104's four categories.** Asked whether to make the paid run of 414 chats with the name and count check off, the maintainer chose to accept the run of 2026-11-18 that PR #117 reported.
- **#113 closes with this PR.**

**What was added.** The [content check eval](evals/verification-overhaul/2026-11-21-content-check.md), with the run's generated report beside it. It is the write-up of the many-foods run of 2026-11-21.

**What it holds**

- **The results beside the earlier runs of the same 20 cases:** the baseline of 2026-11-16, the units run of 2026-11-17 and the full run of 2026-11-18.
- **What went wrong with the check off,** food by food. These are the wrong saves a chat of the app makes.
- **What the content check did,** for each model: the 43 foods it had written again, split into 6 fixed, 10 made wrong and 27 that stayed within tolerance, and the 20 wrong foods it passed.
- **The date check:** 551 dates corrected, and what the chat models had written.
- **Cost and time,** with the time a message with many foods takes beside the baseline's, which #113 asks for.
- **The run against what counts as done,** and the decision D29 with its reason and evidence.

**Two things the write-up says that the entry for D29 does not**

- **Every model is lower than in the full run of 2026-11-18 on these cases,** in both modes, by 3.4 to 11.5 points. The cause is not known. Every wrong save in this run is a nutrition value, which the name and count check does not change, so the report gives no sign that switching that check off is the cause. It may be chance: each figure is one run of about 60 chats. All six figures moved the same way.
- **`claude-haiku-5-5` with the check off is below both of its earlier results with no verification:** 78.3% against 80.0% in the baseline and 85.0% in the units run.

**The figures were checked again.** The counts of fixed, broken and unchanged foods in the entry for D29 were read from the report a second time, row by row, and they hold.

**Other docs.** `AGENT_OPERATIONS.md` and the eval README link to the write-up. In this plan, the acceptance table has each criterion's final status, and steps 13 and 14 are done.

**#113 reworded and ticked**

- The resending criterion has the wording of option A, limited to a check that could not be completed. It names what is not covered and points to [#57](https://github.com/phanmi/tyler_agent/issues/57).
- The criterion about a failure saving none of a message's foods notes that it holds for one `recordFood` call.
- The criterion about the run of #104's categories notes which run meets it, and the decision not to run them again.
- The line on where the numbers come from names the write-up and the report.
- Every criterion that is met is ticked. The two that rest on the name and count check stay unticked and partially met, as decided on 2026-11-18.

**#113 against what counts as done**

| Item | Result |
|---|---|
| 1. Every check tool is implemented, and the content check can be turned off from Settings | The three checks are implemented. The Settings switch was built and then removed (D30): the content check is off in every chat, so there is nothing to turn off. |
| 2. Every #113 criterion is met | Met, apart from two that are partially met by the decision of 2026-11-18: a converted unit, and a food that matches neither the conversation nor a tool result. Both rest on the name and count check, which is off until [#118](https://github.com/phanmi/tyler_agent/issues/118). The resending criterion is met as reworded, which leaves a resend after a save to [#57](https://github.com/phanmi/tyler_agent/issues/57). |
| 3. An eval run shows that the cheap models improve | Met as the plan reads it, with the content check on, in both runs. With the check off, as the app runs, the many-foods run has two models above their baseline and `claude-haiku-5-5` one chat below. |
| 4. No wrong or missing date and no missing food | No wrong or missing date in either run. No missing food on the many-foods cases in either run. In the full run, 12 chats elsewhere saved nothing, 10 of them `some-almonds`, which the maintainer set aside. |
| 5. With the content check on, the results are better than with it off | Not met. D29 was decided from it. |

**The review of this PR.** `/code-review` ran at level `medium` on the branch. It checked the write-up's figures against the generated report and they held. It had six findings, all in the docs of this PR, and each was real.

| | Finding | What was done |
|---|---|---|
| C1 | The resending criterion was marked met with the wording "when an earlier `recordFood` call of the chat saved, the app does not ask for a resend". That holds for `MODEL_ANSWER_UNUSABLE` only. A check that fails another way after a save still answers "Please try again later". | The criterion is limited to a check that could not be completed, and names what is not covered. The maintainer has not confirmed the narrower wording. The other way to settle it is to mark the criterion partially met. |
| C2 | The acceptance row for the many-foods run still said the eval's "off" mode runs the name and date checks. | The row says which checks ran in each run. |
| C3 | The same row said the runs are reported in PR #117 and PR #121, which reads as if #121 held the write-up. | The row says the PR descriptions report the runs, and that the write-ups are under `docs/evals/`. |
| C4 | "By 2 to 6 chats of 60" mixed two totals: `deepseek-flash` had 59 chats in the earlier run. | The difference is given in points, 3.4 to 11.5. |
| C5 | This entry's checks left out `mypy` and gave no results. | Both `mypy` runs were made, and the results are below. |
| C6 | The write-up said every chat made four model requests with the check off. The report gives only the average. | The write-up says each model averaged 4.00. |

**Checks**

- Python, because the eval README changed: `ruff format --check` with 92 files formatted, `ruff check` clean, `mypy` clean for `linux` and `win32` over 91 files, and `pytest tests` with 1009 passed and 1 skipped.
- Java and frontend: not run, because no file of theirs changed.
- No code changed, so no regression test was added.

**Left open after #113**

- **The name and count check** is implemented and off. [#118](https://github.com/phanmi/tyler_agent/issues/118) is to show what it is worth, and the two partially met criteria wait for it.
- **A resend after a save.** A user who resends in spite of the warning still saves the earlier call's foods twice, and the failures other than `MODEL_ANSWER_UNUSABLE` still answer "Please try again later" after a save. [#57](https://github.com/phanmi/tyler_agent/issues/57) is about a later call that fails after an earlier one saved.
- **From the first review of PR #121:** [#119](https://github.com/phanmi/tyler_agent/issues/119) and [#120](https://github.com/phanmi/tyler_agent/issues/120).
- **The unit categories have no run with the name and count check off,** by the decision above.
- **The app was not run by hand** after the Settings switch was removed, and the after-save error text has not been seen in it.
