# Food verification overhaul

[Issue #113](https://github.com/phanmi/tyler_agent/issues/113) | [Agent operations](AGENT_OPERATIONS.md#food-verification) | [Python agent standards](PYTHON_AGENT_STANDARDS.md) | [Eval harness](../agent-python/evals/README.md)

This tracker covers the food verification overhaul in issue #113. The requirements and acceptance criteria are in #113, including those folded in from #98, #100 and #101. This document records the design, the decisions, the eval runs and the progress, so the work can resume one step at a time.

**Direction:** the current verification tool is split into separate tools. Decided by the maintainer on 2026-11-17. Which tools, and what each one checks, is left to the design.

## Current verification tool

| Area | Current state and source |
|---|---|
| Where it runs | [`adapter.py`](../agent-python/src/tyler_agent/tools/builtin/food_record/adapter.py) calls `verified_food` in [`verification.py`](../agent-python/src/tyler_agent/tools/builtin/food_record/verification.py) before it requests `food.insert`. It runs when `RunSettings.verify_food` is on, which is the default; the `run` payload has no field for it, so only code such as the eval runner and tests turns it off. |
| What one check covers | One model request judges the whole record through `submitFoodVerdict {valid, reason}`: the food, the amount and unit, the date, the values the user stated, and whether the values the user did not state are plausible. |
| After a rejection | The model regenerates the record from the verdict's reason, and the new record is checked again. A record rejected `MAX_REJECTIONS` (2) times is refused: it is never sent to Java, and the chat model is told not to call `recordFood` for it again. Each item costs one to three model requests, one after another. |
| What it sees | Today's date from Java, the conversation as text (saved history plus the current message), and the record. It sees no tool results. |
| Which model | The run's own provider and model, through `ToolContext.respond`. |
| How evals count it | [`runner.py`](../agent-python/evals/runner.py) and [`report.py`](../agent-python/evals/report.py) count verification outcomes from the model calls inside each `recordFood` call. |

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

### How the design meets #113

This table checks the scope of #113. The [acceptance criteria](#how-the-acceptance-criteria-are-met) are checked below the proposals.

| #113 asks for | How the design meets it | Met? |
|---|---|---|
| Each rejection's reason in the eval report | The report lists each check's verdict and reason (P7) | Yes |
| A check that does not judge one food against a message that lists many | The name and count check sees every food of the message at once. The content check sees one food and no chat. | Yes |
| No record that holds the whole meal | A record named after a meal, such as "dinner", is an incorrect name: it is removed and the foods it stood for are added as missing | Yes, if the name check's prompt says each record is one food |
| A refusal does not drop a food the user listed | A failed check is corrected, not refused, and each correction runs once (D5). Only a food the name check calls incorrect is removed. | Yes |
| Cost and wait | One name check and one date check per message, one content check per food, and at most one correction each (D5). The content checks run at the same time (P6). | Measured in step 13 |
| One tool that checks only the date | Verify date, then correct date, on each food's date (P2), after the name check has added any missing food (P1) | Yes |
| Some checks in code instead of a model | Python removes incorrect foods | Yes |
| A converted unit is not accepted (from #104) | The name check compares each food's amount and unit with the message, and the correction restores the user's unit (D4) | Yes |
| Whether the date and unit checks run with the switch off | They always run; the switch turns off only the content check (D3) | Yes |
| User-stated values always win (#96) | The food correction sees the chat and keeps every value the user stated, so the user's 1900 kcal banana is saved as stated (D1) | Yes |
| A Settings switch, saved, sent in the `run` payload, protocol version 3 (#98) | The switch turns off the content check (D3). It is saved as the first agent setting (D7) and sent in the `run` payload of protocol version 3, moved from version 2 (D8). | Yes |
| With the switch off, no content check or food correction runs (#98, reworded) | With the switch off, the name and date checks still run (D3) | Yes |
| Checks see the tool results a record was based on (#100) | The name check, the date check and the corrections get this run's tool results (D6, P9) | Yes |
| Tool results cannot change what a check is asked to do (#100) | Tool results stay inside the JSON the checks read (P8) | Yes |
| A failed check saves nothing from the message (#101) | No food is saved until every check has run, and the whole list is saved in one Java transaction (D9) | Yes |
| One list of foods per message | `recordFood` takes every food of the message in one call (D2) | Yes |
| What result on the baseline runs counts as done | Stated under [What counts as done](#what-counts-as-done) | Yes |

### Design decisions

Answered on 2026-11-17. Where these decisions and the proposals differ from the sketch above, they take precedence; for example, P1 runs the name check before the date check.

| | Question | Recommended | Other options | Steps | Answer               |
|---|---|---|---|---|----------------------|
| D1 | How are values the user stated kept? | The content check stays without the chat. The food correction sees the chat and keeps every value the user stated, and a corrected food is saved without a second content check. | `recordFood` marks which values the user stated, and the content check skips them. Or the content check also sees the user's message. | 5, 6 | recommended solution |
| D2 | Where does the list of foods come from? | `recordFood` takes every food of the message in one call, as a `foods` list. | Python collects all `recordFood` calls in one model response, which changes the agent loop. | 2 | recommended          |
| D3 | What does the Settings switch turn off? | Only the content check, as the design's note reads. The date and name checks always run, and #98's "no extra model calls when off" is reworded. | Every check, as #98 says. Or one switch per check. | 5, 8, 9, 10 | recommended          |
| D4 | Does "count" mean how many foods, or how much of each? Where are amount and unit checked? | The name check also gets each food's amount and unit, and reports those that differ from the message, including a converted unit. The correction changes only those two fields. | A separate amount-and-unit check. | 3 | recommended          |
| D5 | How often does a check run? | Each check runs once per message, and each correction once. Corrected values are saved without another check, and an unusable correction keeps the values it was meant to fix. Only the name check removes a food. | Check corrections again up to a limit, then save or refuse. | 3, 4, 5 | recommended          |
| D6 | How do checks see tool results? | The name check and every correction that sees the chat also get this run's tool results, so they judge what the chat model actually read. These also show the foods an earlier `recordFood` call in the run saved. | As the design says: the name check calls `readFile` itself. | 2, 3 | recommended          |
| D7 | Where is the switch saved? | As the first agent setting from #82, with its own model, service, controller and file, so agent behavior stays out of the provider settings. | A field in `LlmSettings`: about half the code, but "Reset provider settings" would also reset it. | 9, 10 | recommended          |
| D8 | How is the contract version kept? | Move `contracts/agent/v2/` to `v3/` with `git mv` and edit it in place, so the PR shows only the real changes. Version 2 stays in git history, and the rule in `docs/AGENT_PROTOCOL.md` changes. | Keep a full copy: about 5,000 lines of fixtures. | 8 | recommended          |
| D9 | How is the list saved? | A new Java operation saves every food of the message in one transaction, added in protocol version 3. | Save one food at a time, and when Java rejects one, tell the user which were saved. | 8, 9 | recommended          |

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

### How the acceptance criteria are met

#113's criteria were reworded on 2026-11-17 to match D1 to D9 and P1 to P10. **Planned** means a step meets the criterion as #113 now words it.

| #113 criterion | How it is met | Steps | Status |
|---|---|---|---|
| A design is approved before code. It says what counts as done on the baseline run, and how it meets #98, #100, #101 and #104's eval. | This plan: [What counts as done](#what-counts-as-done), the scope table above, D1 to D9 and P1 to P10. | 1 | Met on 2026-11-17 |
| A `--cheap-models --category many-items` run is reported beside the baseline. | The eval's "off" mode now means the switch is off, which still runs the name and date checks (D3). So the baseline's no-check numbers come only from the 2026-11-16 report. | 7, 13 | Planned |
| No food is saved until every check that is switched on has run on it. A corrected value is saved without a second check. | The list is saved in one operation after the last check (D9), and each correction runs once (D5). | 6, 9 | Planned |
| Python tests drive the new behavior through the real agent loop, tool registry and `recordFood`. | Each check's tests run through them. | 3 to 6 | Planned |
| One tool checks only that a record's date is correct, with an invented date tested. | Verify date and correct date (P2). | 4 | Planned |
| A unit converted from the user's is not accepted, with the grande latte tested. | The name check reports 16 fl oz, and the correction restores "grande" (D4). | 3 | Planned |
| A `--cheap-models` run of #104's four categories is reported beside #116's run. | Wrong-date chats, and the unit `deepseek-flash` saves for `starbucks-latte`. | 13 | Planned |
| Settings shows a nutrition check switch, "Check nutrition values", that is on when nothing has been saved, and it persists across a restart. | The first agent setting (D7) and the Settings page (P10). | 9, 10 | Planned |
| With the switch off, no content check or food correction runs. The name and date checks still run. | `RunSettings` built from the `run` payload switches the content check (D3). | 5, 8 | Planned |
| A change applies to the next chat, and Java tests prove the `run` payload carries the saved value. | `PythonAgentService` reads the setting when a chat leaves the queue. | 9 | Planned |
| A missing or non-boolean value gets HTTP 400 naming the field, and the frontend shows it. | The settings controller and the Settings page. | 9, 10 | Planned |
| The model's file tools refuse the file that stores the switch. | The setting's service implements `IApplicationOwnedFiles`. | 9 | Planned |
| The version 3 schema and fixtures reject a `run` without `checkNutrition` or with a non-boolean, the fixture tests agree, and a version 2 peer fails with `UNSUPPORTED_VERSION`. | Version 2 moved to version 3 and edited in place (D8). | 8 | Planned |
| Frontend tests cover the default, a save and a failed save. | Settings page tests. | 10 | Planned |
| A food recorded from a file the chat model read can be accepted and saved. | The name check, the date check and the corrections see the file's content (D6, P9). | 2, 6 | Planned |
| The name check, the date check and the corrections receive this run's tool results, and no keys. | A general part of `ToolContext` (D6, P9), and a test that no check request carries a key. | 2, 3, 4 | Planned |
| A food that matches neither the conversation nor a tool result is not saved. | The name check calls it incorrect, and Python removes it. | 3 | Planned |
| Python tests drive the file-based flow through the real agent loop, tool registry and `recordFood`. | The file-based test in step 6. | 6 | Planned |
| Adding another tool needs no change to the registry or the agent loop. | Tools read this run's tool results through a general part of `ToolContext` (D6). The agent loop changes once to provide it, not once per tool. | 2 | Planned |
| A failure while checking a multi-food message saves none of its foods. | Nothing is saved until every check has run (D9). | 6 | Planned |
| Resending after such a failure creates no duplicates. | Nothing was saved, so nothing is saved twice. | 6, 9 | Planned |
| Java saves a message's foods in one transaction: when it rejects one, none is saved. The version 3 contract covers the new operation. | A bridge handler and service method of their own (D9). | 8, 9 | Planned |
| The time a multi-food message takes with the checks on is measured before and after. | The eval reports each chat's reply time. | 13 | Planned |
| Python tests cover a failure and a cancellation between checks through the real agent loop and runtime. | The failure and cancellation tests in step 6. | 6 | Planned |
| `AGENT_OPERATIONS.md` describes the new behavior, including what each check sees. | What each check sees. | 11 | Planned |
| `AGENT_PROTOCOL.md`, `DEVELOPMENT.md`, `BACKEND_STANDARDS.md` and the README's Settings section describe the switch. | Including version 3 and D8's rule change. | 11 | Planned |
| The Python checks, `mvn test` and `npm run check` pass. | Run after every area is done. | 12 | Planned |

## Steps

| Step | Work | Status | Evidence |
|---|---|---|---|
| 1 | Get the design approved: D1 to D9 answered, what counts as done stated, and #113's wording updated where the design changes it. | Done | D1 to D9 and P1 to P10 answered, #113 updated to match, and what counts as done stated and agreed, on 2026-11-17. |
| 2 | `recordFood` takes the message's foods as a list, held in memory for the call (D2). Tools can read this run's tool results through a general addition to `ToolContext`, not one for `recordFood` alone (D6). The current verifier still runs on each food until step 6. | Not started | |
| 3 | Shared check machinery: one request offering one check tool, and an answer that cannot accept by accident. Then the name and count check, with amount and unit (D4, P3): the model adds missing foods and Python removes incorrect ones. Tests: a whole-meal record is replaced by its foods, a missing food is added, a converted unit is corrected, and a food from an earlier message is not added again. | Not started | |
| 4 | Date check and date correction (P2), with this run's tool results (P9). Tests: an invented date is corrected, foods on different days keep their own dates, and a date taken from a file the chat model read is kept. | Not started | |
| 5 | Content check and food correction (D1, P4), one per food, run at the same time (P6), switched by `RunSettings` (D3). Tests: the user's 1900 kcal banana is kept, and an implausible estimate is corrected. | Not started | |
| 6 | The checks run in P1's order in place of `verified_food`, and no food is saved until every check has finished. The old verifier, `submitFoodVerdict` and their tests are removed. Tests through the real agent loop and runtime: a failure or a cancellation between checks saves nothing, and a food from a file the chat model read is saved. | Not started | |
| 7 | Eval harness: record each check's verdict and reason, and list rejections in the report (P7). `FakeJava` answers the new save operation. | Not started | |
| 8 | Protocol version 3 (D8): the switch field in `run`, and the operation that saves a list of foods (D9). Update the schema, the fixtures, `MessageValidator`, `MessageCodec`, the Python validation, the version constants, and the contract paths in `pom.xml`, `worker.spec`, `tests/desktop-worker.spec` and `tests/conftest.py`. A version 2 peer fails with `UNSUPPORTED_VERSION`. The runtime builds `RunSettings` from the payload. | Not started | |
| 9 | Java: the switch's storage (D7), implementing `IApplicationOwnedFiles`, which rejects a missing or non-boolean value with HTTP 400 naming the field. `PythonAgentService` sends the value when a chat leaves the queue. A bridge handler and service method save a list of foods in one transaction, and `recordFood` uses it. | Not started | |
| 10 | Frontend: the switch in Settings, its description of the extra model calls, saving, and showing a failed save. Tests cover the default, a save and a failed save. | Not started | |
| 11 | Docs: `AGENT_OPERATIONS.md` (what each check sees), `AGENT_PROTOCOL.md`, `DEVELOPMENT.md`, `BACKEND_STANDARDS.md`, the README's Settings section, and the eval README. | Not started | |
| 12 | Checks: Python, `mvn clean test` and `npm run check`. Show that each new regression test catches its bug. Run `/code-review` on the branch and resolve what it finds. | Not started | |
| 13 | Paid eval runs, after your go-ahead: `--cheap-models --category many-items`, and `--cheap-models` on #104's four categories, each compared with its baseline. Measure how long a multi-food message takes, before and after. Check the results against [What counts as done](#what-counts-as-done), and write them up under `docs/evals/`. | Not started | |
| 14 | Open the PR with the template filled in, and tick the #113 criteria that were checked. | Not started | |

## Decisions

| Date | Decision | Source |
|---|---|---|
| 2026-11-17 | Split the current verification tool into separate tools. | Maintainer, when this plan was set up |
| 2026-11-17 | D1 to D9: the recommended option for each. P1 to P8 accepted. | Maintainer, in the tables under Design |
| 2026-11-17 | P9 and P10 accepted. #113's scope and acceptance criteria updated to match the design, with one new criterion for D9's one-transaction save. | Maintainer |
| 2026-11-17 | What counts as done: every check implemented and the content check switchable from Settings, every criterion met, the cheap models improve, no wrong date and no missing food, and better results with the content check on. How the eval is read against it, as recommended. | Maintainer |

Decisions made before this plan are in #113's description.

## Execution record

_One entry per completed step or eval run, oldest first: the date, what changed, the checks or eval run and their results, and anything left open._
