# Java to Python agent migration

[Developer guide](DEVELOPMENT.md) | [Backend standards](BACKEND_STANDARDS.md) | [Protocol contract](AGENT_PROTOCOL.md)

This tracker covers moving agent orchestration, OpenAI communication, and tool adapters from Java to Python while retaining Java's HTTP API, domain services, and persistence. It records the roadmap, completed changes, verification, and remaining decisions so work can resume one step at a time.

**Direction:** Java to Python, confirmed on 2026-10-16.

**Current state (refreshed 2026-10-18):** Steps 2–4 are complete. Committed baseline `49be021` includes the Java validation/session refactor and Python CI checks. The current follow-up fixes the Windows-only startup guard for Linux type checking and configures both Linux and Windows mypy targets. Both languages now have shared wire validators and focused session handlers. The [session lifecycle guide](AGENT_SESSION.md) explains the states and message reactions, distinguishing the pure observers from runtime integration. Application chat still uses the Java agent; Step 5 process supervision remains next. Earlier checkout and CI observations remain historical evidence.

## Current implementation baseline

| Area | Verified current state and source |
|---|---|
| Food validation | [FoodService](../src/main/java/org/tyler/service/food/FoodService.java) invokes the injected `IValidator<Food>` implemented by [FoodValidator](../src/main/java/org/tyler/validation/FoodValidator.java) before DAO insertion. Required fields, real calendar dates, and nonnegative amounts/nutrition are shared domain rules. [RecordFoodTool](../src/main/java/org/tyler/tool/foodRecordTool/RecordFoodTool.java) deserializes arguments, delegates to `IFoodService`, and serializes the food-only result. |
| Workout and plan validation | [WorkoutValidator](../src/main/java/org/tyler/validation/WorkoutValidator.java) enforces workout rules through the workout service. [WorkoutPlanService](../src/main/java/org/tyler/service/workout/WorkoutPlanService.java) owns plan date/goal/equipment validation, including the seven-representable-days guard. [GenerateWorkoutPlanTool](../src/main/java/org/tyler/tool/workoutPlanTool/GenerateWorkoutPlanTool.java) checks JSON shape and passes missing/null text fields to the service. |
| HTTP validation | [GenericExceptionHandler](../src/main/java/org/tyler/exceptionHandler/GenericExceptionHandler.java) explicitly maps missing required parameters and argument type mismatches to HTTP 400. [DomainValidationContractTest](../src/test/java/org/tyler/service/DomainValidationContractTest.java) exercises real shared services behind HTTP and tool adapters. |
| Frontend checks | [package.json](../frontend/package.json) includes `typecheck`, `test:ui`, `test:startup`, and `test:version`. Tests/helpers live under [frontend/tests](../frontend/tests); [Vitest configuration](../frontend/vitest.config.ts) selects `tests/**/*.test.{ts,tsx}`. These changes are already merged. |
| CI | [CI workflow](../.github/workflows/ci.yml) now configures locked Python dependency installation, Ruff format/lint, strict mypy for Linux and Windows targets, and `pytest tests` in the existing Build and test job before Java 26 `mvn --batch-mode clean verify`, then Node 22 `npm ci`, supporting tests, production build, type checking, and UI tests. [Run 38950225490](https://github.com/phanmi/tyler_agent/actions/runs/38950225490) passed all **261 backend tests** for `4ee8cbe`, as reported in the review. This supersedes the earlier reported 256-case CI baseline. |
| Step 4 implementation | [Python project and commands](../agent-python/README.md), [Python codec](../agent-python/src/tyler_agent/protocol/protocol.py), and [Java protocol infrastructure](../src/main/java/org/tyler/service/agent/protocol) implement framing/validation and pure sequence observation. The worker is a readiness/shutdown prototype; valid runs fail explicitly as unavailable. No model call or service operation is implemented. |
| Latest local verification | On `49be021` plus the platform-guard fix, Ruff formatting/lint, mypy targeting both Linux and Windows, **218 Python development tests**, and **231 tests including the rebuilt relocated Windows worker** passed. The preceding Java refactor passed **570 backend tests**, including **309 protocol tests**; Java was not rerun for this Python-only fix. |

Step 4 and its protocol review corrections are included in the committed baseline `49be021`; the latest local verification includes this follow-up's changes on that revision. The older agent-test correction remains committed in `4ee8cbe`. Frontend checks were not rerun for this backend/Python infrastructure work. Step 1 remains partial until its remaining inventory and frontend verification evidence gates are satisfied; a clean-machine Windows installer check remains Step 9.

## Progress

| Step | Work | Status | Completion evidence or remaining gate |
|---|---|---|---|
| 1 | Establish the migration baseline | Partially complete | Source revision `2158f10`, included test correction, shared validation, and frontend/CI configuration reconciled above. Finish the contract/startup inventory and frontend verification evidence. |
| 2 | Characterize the Java agent | Complete | The 35 agent and 14 factory cases still pass in the latest 570-case backend run. Earlier CI/local results remain dated below. |
| 3 | Define the Java and Python protocol | Complete | Version 1 specification/schema; shared fixtures contain 100 message cases, 24 framing cases, and 38 sequence scenarios after the numeric simplification. |
| 4 | Create and package the Python worker skeleton | Complete | Locked Python environment, checks, Java/Python fixture conformance, and credential-free development/relocated Windows bundle smoke tests pass. See the Step 4 record. |
| 5 | Implement Java worker lifecycle and adapter | Not started | Fake-worker tests prove request routing, callbacks, failures, and process cleanup. |
| 6 | Expose Java service operations | Not started | Allowlisted handlers preserve service validation, result formats, and storage guarantees. |
| 7 | Port the agent loop and tool adapters | Not started | Python passes the behavior baseline with a fake model provider. |
| 8 | Verify the complete persistence path | Not started | Real subprocess and temporary-database tests cover success, rollback, and interrupted writes. |
| 9 | Integrate desktop packaging and CI | Not started | Clean-machine Windows smoke tests and required CI checks pass. |
| 10 | Roll out Python and retire the Java agent | Not started | Default switched, rollback window completed, obsolete code and references removed. |

The roadmap describes future work. A planned configuration, component, or test below must not be treated as already available.

Each step's definition of done is its acceptance checklist. Check an item only when the execution record identifies the supporting change or verification. A step is complete only when every item passes; deferred items keep it partial. Use the current implementation baseline for new work and the dated execution records for past results; test totals always identify the tested revision and local changes.

## Target architecture and boundaries

Java will own the Python child process and communicate over private standard input/output pipes. Python will have no additional listening port. Electron and the development launcher will continue starting Java.

```mermaid
flowchart LR
    UI[React and Electron] --> HTTP[Java HTTP API]
    HTTP --> Adapter[Java agent adapter]
    Adapter <-->|Process pipes| Python[Python agent worker]
    Python --> Provider[OpenAI Responses API]
    Python <-->|Operation requests and results| Bridge[Java operation bridge]
    Bridge --> Services[Domain services and file sandbox]
    Services --> Storage[SQLite and workspace files]
```

| Responsibility | Target owner |
|---|---|
| Provider client, model requests, response chaining, and reply extraction | Python |
| Tool definitions, model-argument parsing, and tool orchestration | Python |
| Public chat and history HTTP contracts | Java |
| API-key storage, profile storage, and history persistence | Java |
| Shared domain validation, food/workout operations, and plan templates | Java services |
| Database transactions, DAOs, SQL resources, and sandbox restrictions | Java |
| Python startup, readiness, shutdown, and request correlation | Java process adapter, with worker cooperation |

Migration constraints:

- Preserve HTTP routes, JSON fields, status mappings, tool names, argument schemas, and tool result formats unless an explicit contract change is approved.
- Keep Python tools behind the shared Java service boundary. Do not give Python direct SQLite access or duplicate domain rules there.
- Preserve stable record IDs, existing data, plan duplicate policy, and non-overwriting behavior. Sequential repeat behavior does not imply concurrent deduplication.
- Keep each atomic service operation in its existing Java transaction. A conversation containing several tools is not one database transaction.
- Do not replay a conversation or write operation automatically after an uncertain result, or silently fall back to Java after tools may have run.
- Keep credentials out of process arguments and logs. Transfer the credential snapshot through the private pipe and refresh it for later requests after a key change.
- Use the existing global release-version source. A protocol compatibility version is a separate concept.
- All agent functionality must be implemented in Python under the [backend standards](BACKEND_STANDARDS.md#agent-implementation-language). Existing Java agent code is temporary migration/rollback code; the retained Java process adapter and operation bridge implement infrastructure and service access, not agent decisions.

## Executable roadmap

### Step 1 Establish the migration baseline

1. Use the recorded merged HEAD as the current reference; reconcile any subsequent merges and local changes before implementation.
2. Continue on the existing `python-agenticfeature` migration branch. Record a new branch/base if that changes.
3. Inventory current HTTP/tool contracts, validation, history behavior, and process startup.
4. Run backend and frontend baseline checks and record their results. Use temporary workspaces and databases for tests.

**Definition of done**

- [x] Record the intended source branch, base commit, migration branch, and disposition of existing working-tree changes. See the current implementation baseline and dated merged-HEAD refresh below.
- [ ] Inventory the public chat/history HTTP contracts and all six tool names, schemas, outputs, validation, and side effects, with source references.
- [ ] Record startup ownership, readiness, storage paths, release-version handling, and the target Java/Python responsibility split.
- [x] Reconcile earlier pending frontend work with the chosen baseline. `typecheck`, `test:ui`, and the dedicated `frontend/tests/` directory are included in the merged HEAD and wired into CI.
- [ ] Run the selected baseline's backend and frontend checks successfully and record commands, runtime versions, and results. Step 2's backend run can be reused only if the tested code still matches the selected baseline.

### Step 2 Characterize the Java agent

1. Exercise the real `AgentService` with deterministic provider responses and real SDK message objects.
2. Cover plain replies, tool rounds, ordered calls, function-call IDs, response chaining, and the five-round boundary.
3. Cover text, refusals, empty replies, unknown tools, invalid arguments, authentication, provider, tool/storage, and history failures.
4. Verify history is appended once after successful completion and that failures do not cause application-level replay.
5. Verify client caching, key rotation/removal, and current-client lookup for subsequent requests.
6. Record edge cases as current behavior; do not silently change them during characterization.

**Definition of done**

- [x] Deterministic tests execute the real Java agent with mocked provider responses and real SDK messages, without live requests or credentials.
- [x] Tests verify initial history/message/model inputs, complete tool definitions, ordered calls, unchanged arguments/results, call IDs, and response chaining.
- [x] Tests cover plain text, refusals, empty output, and both successful completion and unresolved calls at the five-round limit.
- [x] Tests assert exact exception behavior for unknown tools, malformed arguments, authentication/provider failures, tool validation/storage failures, and history failures, retaining causes or exception identity as applicable.
- [x] Tests verify one successful exchange append, no exchange on earlier failures, no application-level replay, and fresh client/history lookup on later requests.
- [x] Factory tests verify deferred key lookup, unchanged-key reuse, changed-key replacement, missing/removed keys, and key-read failures.
- [x] Focused and full backend suites pass; preserved edge cases and verification limitations are recorded below. Production behavior is unchanged.

### Step 3 Define the communication contract

1. Specify versioned UTF-8 newline-delimited JSON messages: `ready`, `run`, `operation_request`, `operation_result`, `completed`, `failed`, `cancel`, and `shutdown`.
2. Define request IDs, operation-call IDs, payload schemas, size limits, timeouts, and handling of unknown/stale messages.
3. Distinguish validation, authentication, provider, storage, and worker failures while preserving existing HTTP error behavior.
4. Define decimal/date/null serialization without losing precision or narrowing previously accepted values.
5. Reserve stdout for protocol messages and stderr for logs. Add shared valid and invalid message fixtures.

**Definition of done**

- [x] A versioned contract defines every message type, required/optional fields, direction, valid sequencing, and terminal outcomes.
- [x] Framing, encoding, message-size limits, request/operation correlation, and handling of unknown, duplicate, stale, and incompatible messages are specified.
- [x] Every failure category has a documented Java/HTTP mapping; cancellation, timeout, and lost-result behavior explicitly prohibit automatic write replay.
- [x] Decimal, date, null, escaping, and tool-result representations preserve the baseline contracts without precision loss or narrower accepted inputs.
- [x] Shared valid/invalid fixtures state the expected accepted value or rejection reason, including multiline/Unicode content and incompatible protocol versions.
- [x] Credential transfer, stdout/stderr separation, and redaction rules are specified. The contract identifies the executable fixture checks to be completed in Step 4 before lifecycle integration.

Step 3 defines the contract and fixture expectations in [agent protocol version 1](AGENT_PROTOCOL.md). Its completed checks are recorded in [the Step 3 execution entry](#2026-10-16-step-3-completed). Step 4 establishes the Python environment and proves both languages implement them, avoiding a dependency on a worker that does not exist yet.

### Step 4 Create the Python project and packaging prototype

1. Add `agent-python/` with `pyproject.toml`, application code under `src/tyler_agent/`, and all tests/helpers/fixtures under `tests/`.
2. Select and pin the Python runtime, the official OpenAI SDK, development tools, and a reproducible dependency lock.
3. Configure formatting, linting, type checking, and unit tests.
4. Implement a minimal worker that exchanges protocol messages without contacting a model provider.
5. Prove the worker can be packaged for Windows and started without a separately installed Python environment.

**Definition of done**

- [x] A fresh checkout can create the documented Python environment from pinned runtime/dependency specifications and the committed lock. A new isolated environment was installed and smoke-tested from the checked-in lockfile content; see the record below.
- [x] Python source lives under `agent-python/src/tyler_agent/`; tests and helpers live under `agent-python/tests/`. Language-neutral fixtures remain shared in `contracts/agent/v1/fixtures/`, as specified by Step 3.
- [x] Documented formatter, lint, type-check, and unit-test commands pass; no live credentials or paid provider calls are required.
- [x] Java and Python codec/validation tests pass the Step 3 fixtures, including rejection of malformed and incompatible messages.
- [x] Development and packaged worker prototypes complete the versioned readiness handshake without an API key or provider access, with protocol-only stdout.
- [x] The packaged worker launches on Windows without a separately installed Python runtime or startup dependency downloads; the build command and smoke-test environment are recorded.

### Step 5 Implement the Java adapter and process lifecycle

1. Add `PythonAgentService` behind the existing `IAgentService` interface. Keep Java responsible for loading credentials/history and appending the completed exchange.
2. Introduce explicit `agent.implementation=java|python` selection during migration; exactly one implementation serves requests.
3. Give Java sole ownership of worker startup and shutdown. Process callbacks independently while the original HTTP request waits, avoiding a deadlock.
4. Define a bounded request queue and correlation for the single persisted conversation so histories and tool results cannot mix.
5. Preserve the existing backend readiness nonce/port protocol; announce readiness after worker compatibility is confirmed, without requiring an API key.
6. Cover timeouts, cancellation, worker crash, normal shutdown, pipe closure, and Java termination. Verify Python exits even during a pending model request on Windows.

**Definition of done**

- [ ] Configuration selects exactly one `IAgentService` implementation; the existing HTTP chat/history response contracts remain compatible and selection is tested.
- [ ] Java is the sole worker process owner. Readiness preserves the existing nonce/port handshake and waits for worker compatibility without requiring a valid API key.
- [ ] Tests prove operation callbacks are processed while the chat request waits, with no deadlock or cross-request result/history mixing.
- [ ] Queue limits, request deadlines, and cancellation behavior are documented and tested, including rejection of late responses and prevention of further tool calls after cancellation.
- [ ] Each request receives the current credential snapshot; missing/changed keys and provider/storage/worker errors retain the documented behavior without leaking credentials.
- [ ] Fake-worker tests cover startup failure, protocol mismatch, crash, timeout, and lost results without conversation replay or silent fallback.
- [ ] Normal shutdown, startup cancellation, pipe closure, and forceful Java exit terminate the worker on Windows, including during pending provider work; evidence includes process-exit checks.

### Step 6 Expose Java operations through an allowlisted bridge

1. Map the six existing tools to narrow handlers: `recordFood`, `generateWorkoutPlan`, `GetUserInfo`, `readFile`, `writeFile`, and `getCurrentTime`.
2. Delegate to existing services or sandbox abstractions. Do not expose arbitrary Java method calls, SQL execution, or unrestricted paths.
3. Preserve the existing service validation boundary when bypassing Java tools: `FoodService` invokes `FoodValidator`; workout and plan services enforce their domain rules. Inventory adapter parsing/coercion and output formatting separately. Do not relocate or duplicate shared domain rules in Python or bridge handlers.
4. Preserve tool-name capitalization, result formatting, date semantics, file restrictions, and exception distinctions.

**Definition of done**

- [ ] Every existing tool has an explicit allowlisted operation mapping and service/sandbox destination; the bridge cannot invoke arbitrary methods, SQL, or paths.
- [ ] Dependency/wiring checks prove handlers call the shared services or sandbox abstractions, without DAO/DAL access from agent adapters.
- [ ] Existing adapter shape/parsing rules are inventoried and preserved when bypassing the legacy adapter. Bridge handlers call the existing services, including their shared validators; tests cover missing fields, malformed dates, negative values, and plan date bounds without duplicating those rules in Python.
- [ ] Contract tests preserve tool-name capitalization, argument/result formats, decimal/date semantics, record IDs, and exception distinctions.
- [ ] Tests reject malformed/unsupported operations before side effects and retain sandbox path restrictions and persistence safeguards.
- [ ] Service tests still verify preview without writes, transactional plan saving, sequential duplicate handling, and preservation of pre-existing edits. No concurrent deduplication guarantee is added.

### Step 7 Port the agent loop and tool adapters

1. Build the initial model request from the supplied configuration, history, and message.
2. Execute returned calls in their existing order and route business operations through Java.
3. Submit outputs with their original call IDs and continue using the previous response ID, within the characterized round limit.
4. Preserve model selection, schemas, descriptions, text/refusal extraction, and failure behavior.
5. Configure SDK timeout/retry behavior explicitly. Avoid conversation/write replay after an uncertain outcome.

**Definition of done**

- [ ] Model calls, context assembly, tool definitions/parsing/dispatch, response chaining, and reply extraction execute in Python; the Java bridge contains no agent decision logic.
- [ ] A parity matrix maps each Step 2 behavior to an equivalent Python test or an explicitly documented contract change; all mapped tests pass with a fake provider.
- [ ] All six tool adapters preserve their names, descriptions, complete schemas, argument handling, and result formats while calling Java operations for domain/storage work.
- [ ] Tests prove ordered calls, original call-ID linkage, the round-limit behavior, text/refusal extraction, and correct success/failure completion messages.
- [ ] Timeout/retry settings are explicit; failure after a completed operation does not replay tools, the conversation, or a fallback implementation.
- [ ] Python keeps history and credential persistence in Java and does not open application databases or bypass the file sandbox.
- [ ] Required Python checks and affected Java tests pass. Any side-effect comparisons use separate temporary data stores rather than user data.

### Step 8 Verify the complete persistence path

1. Exercise HTTP to Java to a real Python subprocess to Java services, using a fake model provider and temporary databases.
2. Check food append semantics and identity, plan repeat behavior, preserved user edits, and actual transaction rollback after an insert failure.
3. Check invalid inputs, provider/storage failures, and history completion behavior through the full boundary.
4. Simulate worker failure before a write and after a write but before its result returns; assert persisted state and no replay.
5. Cover cancellation, concurrent incoming requests, and shutdown during active work.

**Definition of done**

- [ ] Integration tests execute the real HTTP/Java/Python/Java-service path with a real subprocess and temporary SQLite databases, mocking only the external model provider where needed.
- [ ] Food insertion preserves older records and stable IDs; independently saved equal entries remain addressable through existing read/delete paths.
- [ ] Successful and sequential repeated plan saves preserve the existing duplicate policy, IDs, and manual edits; preview remains read-only.
- [ ] An injected failure after one or more plan inserts leaves no partial plan and preserves pre-existing workouts, verified by database queries after rollback.
- [ ] Validation, authentication, provider, storage, and history failures retain their documented response/completion behavior; successful history exchanges are saved once.
- [ ] Worker failure before a write and after a committed write but before its result returns produces the expected database state with no automatic replay.
- [ ] Cancellation, concurrent incoming requests, late results, and shutdown tests show no cross-request contamination, subsequent cancelled tool execution, or orphan worker.
- [ ] Integration commands, environments, outcomes, and remaining limitations are recorded; mocked delete/cleanup assertions are not accepted as rollback evidence.

### Step 9 Integrate desktop packaging and CI

1. Bundle the worker outside ASAR alongside the Java runtime and JAR. Java remains the worker's process owner.
2. Derive release metadata from `.mvn/maven.config`; install dependencies during development/build, never application startup.
3. Add Python static checks/tests and Java/Python integration checks while retaining backend/frontend verification.
4. Add a Windows packaged-app smoke gate. Test paths with spaces/non-ASCII characters, missing/incompatible workers, startup failure, active-request quit, and forceful Java exit.

**Definition of done**

- [ ] Reproducible packaging includes the worker outside ASAR with the Java runtime/JAR, and release metadata derives from the existing global version source.
- [ ] Required CI checks include locked dependency installation, Python formatting/lint/type checks/tests, Java verification, cross-process integration tests, and the selected frontend checks/build.
- [ ] A Windows packaged-app smoke check runs successfully and is a required release/startup-change gate, with the tested artifact and environment recorded.
- [ ] A clean Windows environment runs the artifact without separately installed Java/Python or runtime dependency downloads; Java still owns worker lifecycle.
- [ ] Smoke tests cover spaces/non-ASCII paths, missing/corrupt/incompatible workers, startup timeout/failure, normal quit, quit during startup or an active request, and forceful Java exit.
- [ ] Shutdown/failed-start checks verify no worker remains. Protocol output, credentials, and logs respect the documented boundaries.
- [ ] Developer setup, build, troubleshooting, and release instructions match the commands and artifacts verified by CI/smoke tests.

### Step 10 Switch defaults and retire the legacy agent

1. Enable Python in development, then switch the default only after the preceding gates pass.
2. Retain Java as a configuration-and-restart rollback option for one validated release. Do not replay in-flight requests during rollback.
3. After the rollback window, remove obsolete Java orchestration/tools and the OpenAI Java dependency, following a caller inventory.
4. Update architecture checks to cover the bridge and Python persistence boundary. Remove obsolete references from tests and documentation.
5. Update installation, development, testing, logging, lifecycle, packaging, and recovery documentation and applicable standards.

**Definition of done**

- [ ] Steps 1-9 have passed their acceptance criteria, and the release/commit selected for rollout is recorded.
- [ ] Python is the tested default in development and packaged runs, preserving HTTP/tool contracts and existing persisted data.
- [ ] During one validated release, switching configuration and restarting restores the legacy implementation without replaying in-flight requests; the rollback exercise and release are recorded.
- [ ] After that window, a caller inventory precedes removal of obsolete Java agent/tool code, Java provider dependencies, and the temporary implementation selector. Remaining Java adapters implement infrastructure/service access only.
- [ ] Production code, tests, configuration, and active documentation contain no obsolete legacy-type references; dated historical records may retain them as history.
- [ ] Architecture checks enforce Python agent ownership and the shared service boundary; the full required test/build/package suite passes after removal.
- [ ] Standards, setup, operation, testing, and recovery documentation match the final architecture. The replacement recovery procedure uses a previously validated release after the legacy implementation is removed.

## Execution record

### 2026-10-16 Step 2 completed

Historical source: `f18aa42`, with characterization changes then uncommitted. This checkout preceded the merge recorded in the current baseline; it lacked the later frontend `typecheck`/`test:ui` suites and dedicated testing directory. At that time, required/nonnegative food checks were in the tool, the food service required only a nonblank date, and the plan service lacked the upper-date guard. These observations must not guide new bridge implementation. Migration work was subsequently committed in `b8355ac` and merged with `main` in `92290ae`.

The then-existing Java implementation was characterized before any migration of production code:

- Expanded [AgentServiceTest](../src/test/java/org/tyler/service/agent/AgentServiceTest.java) from 2 to 30 executed cases.
- Expanded [OpenAIClientFactoryTest](../src/test/java/org/tyler/service/agent/OpenAIClientFactoryTest.java) from 3 to 14 executed cases. Constructed SDK clients use synthetic keys and are closed during test cleanup.
- Added assertions for full overridden tool schemas using the real plan-tool adapter, alongside default no-argument schemas.
- Verified real plan-tool parsing failures without invoking its application service.
- Added exact exception-type and retained-cause/identity assertions, ordered call/output checks, history checks, and checks that completed tools are not replayed.
- Added a developer-guide entry for the baseline. The detailed record now lives in this tracker.

Tests use mocked provider and history boundaries. They establish agent orchestration behavior, not real database rollback, SDK transport retries, live model behavior, Python interoperability, or packaged lifecycle behavior. Those checks belong to later steps. Frontend checks were not run for this backend-test change.

### Preserved edge cases

These are observations from the Step 2 run above. Use the current merged characterization suite to verify parity; historical observations do not override later shared-service rules.

| Case | Behavior observed in that run |
|---|---|
| Five tool rounds complete but the last response requests another tool | That extra call is not executed. Text/refusals from the last response are returned and saved; a tool-only response produces an empty reply. There is no round-limit exception. |
| Empty response or message | An empty reply is returned and appended to history. |
| Multiple text/refusal parts | Final-response parts are concatenated in order without added separators. Intermediate response text is not included. |
| Provider authentication/permission failure | `OpenAIKeyException` retains the provider exception as its cause. Other provider failures propagate unchanged. |
| Tool/provider failure after an earlier tool completed | Processing stops without a history exchange or application-level retry. The agent performs no rollback of completed tool effects. |
| History saving fails after a reply | The failure propagates; provider requests and completed tools are not replayed. |
| Key changes or is removed | The next request re-reads the key. A changed key creates a replacement client; a missing key blocks chat despite a cached client. |

### Verification record

| Run on 2026-10-16 | Result | Follow-up |
|---|---|---|
| Initial focused run with Mockito self-attachment | Interrupted before a completed test result; local Windows JVM attachment failed to open its pipe. | Load Mockito explicitly through the test JVM's `-javaagent` option. |
| Focused run with explicit Mockito agent | 41 cases reported, 0 assertion failures, 1 fixture error. The SDK `InternalServerException` builder required a status code. | Corrected the test fixture to specify HTTP 500. |
| Final focused run | 44 tests passed: 30 agent and 14 factory cases; no failures/errors/skips. | Successful baseline at 14:04:05 EDT. |
| Full backend suite | 153 tests passed; no failures/errors/skips; `BUILD SUCCESS`. | Completed at 14:04:30 EDT. |
| Diff whitespace check | `git diff --check` passed. | No whitespace errors. |

The runs used Java 26, OpenAI Java SDK 4.54.0, and Mockito 5.23.0. The full-suite output was saved locally at `target/agent-baseline-full-test.log`; Surefire reports are under `target/surefire-reports/`. These generated files are not durable tracked evidence and can be replaced by a later build; this dated summary records their verified results.

Standard commands from the repository root:

```powershell
mvn "-Dtest=AgentServiceTest,OpenAIClientFactoryTest" test
mvn test
git diff --check
```

The successful local runs used offline Maven, the existing local dependency cache, forked compilation, and explicit Mockito agent loading. The following reproduces those options using paths derived from the local user profile:

```powershell
$testRepository = Join-Path $env:USERPROFILE '.m2/repository'
$mockitoAgent = Join-Path $testRepository 'org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar'
$testJvmOptions = '-javaagent:"' + $mockitoAgent + '"'
$env:MAVEN_OPTS = '-Duser.home="' + $env:USERPROFILE + '"'

mvn -o "-Dmaven.repo.local=$testRepository" '-Dmaven.compiler.fork=true' "-DargLine=$testJvmOptions" '-Dtest=AgentServiceTest,OpenAIClientFactoryTest' test
mvn -o "-Dmaven.repo.local=$testRepository" '-Dmaven.compiler.fork=true' "-DargLine=$testJvmOptions" test
```

Offline execution requires dependencies to be cached already. The Mockito path reflects the tested dependency version; update it if that dependency changes. These runner options were supplied locally and did not change the project build configuration.

### 2026-10-16 Migration tracker established

Created this tracker and linked it from the developer guide. Moved the detailed baseline observations here to keep the roadmap and execution history together. Verified relative file links, the linked execution-record heading, PowerShell example syntax, and diff whitespace. The command examples were parsed without execution; the backend results above are from the completed Step 2 runs.

### 2026-10-16 Acceptance criteria and language ownership clarified

Added a definition-of-done checklist to every roadmap step, retaining Step 2's verified completion status and leaving future criteria unchecked. Separated protocol specification/fixtures in Step 3 from executable Java/Python conformance checks in Step 4. Updated the backend standards to require Python for agent functionality while preserving Java's HTTP, infrastructure, domain-service, and persistence responsibilities. This documentation change does not implement the Python worker or rerun the Step 2 suites.

Documentation verification passed: all ten steps have acceptance checklists, checked items match the recorded completion state, relative links and heading anchors resolve, and whitespace checks pass.

### 2026-10-16 Step 3 completed

**Changes**

- Added [the version 1 protocol specification](AGENT_PROTOCOL.md) and [closed JSON Schema](../contracts/agent/v1/message.schema.json) for all eight message types and six allowlisted Java operations.
- Added shared [message](../contracts/agent/v1/fixtures/messages.json), [framing](../contracts/agent/v1/fixtures/framing.json), and [sequence](../contracts/agent/v1/fixtures/sequences.json) fixtures. They define transport expectations for both future implementations; language-specific tests will consume these files rather than maintain separate copies.
- Defined private process pipes, session/request/operation correlation, directions, framing, deadlines, queue bounds, cancellation, shutdown, terminal/history sequencing, and handling of duplicate/stale/incompatible messages.
- Specified exact decimal strings and full-range ID strings, expanded date strings, explicit nulls, Unicode/escaping, and the existing model-visible result representations. No domain date/numeric rules were tightened. New frame/queue/deadline limits are explicitly documented operational limits for rollout.
- Recorded error categories and the current Java/HTTP mappings, credential snapshots and redaction, and Java's authoritative write-outcome ledger. A lost result or cancelled worker never authorizes automatic replay or proves that a Java write rolled back.
- Linked the contract from the developer guide and backend standards. Python retains agent decisions and model-facing adapters; Java retains process infrastructure, shared services, and storage. No production Java/Python code, HTTP API, schema, or user data changed in this step.

**Review corrections**

The source compatibility review preserved legacy file-argument HTTP 500 behavior and profile record-style text, and identified timing details that are now explicit: a Python timeout can occur while a Java write is pending, a request can cross cancellation without being dispatched, and the next chat waits for Java history persistence. Decimal wording now reflects Java's normalization of negative zero; malformed ID syntax and numeric overflow have distinct rejection stages. Sequence review corrected a forwarded error so its category/code/message remain unchanged and documented a worker claim that must not override Java's unresolved-write ledger.

**Verification on 2026-10-16**

| Check | Result and scope |
|---|---|
| Strict Ajv 2020-12 schema compilation and message cases | 90/90 expectations passed: 44 structurally valid, 46 invalid. Invalid-case rejection precedence also matched. Five valid shapes separately specify semantic `INVALID_VALUE` expectations for Step 4. |
| Independent PowerShell `Test-Json` check | 90/90 shape expectations passed with PowerShell 7.6.5 using the command published in the protocol document. |
| Raw framing fixture audit | 24/24 expectations passed: chunk boundaries, split UTF-8, escaping, exact decoded values, malformed/duplicate JSON, invalid bytes/surrogates, version rejection, and size limits. The boundary frames contain exactly 16,777,216 and 16,777,217 bytes before LF. |
| Sequence fixture integrity | 30 scenarios / 136 steps checked. All 121 wire messages pass the schema after resolving references and all 18 existing-field replacements; 15 local events are recognized. Directions and expected state/effect rules were reviewed. No application state machine was executed. |
| Documentation and whitespace | All 51 relative file links/heading anchors in the four affected documents resolve; Step 3's six checked acceptance items verified. New contract files have no trailing whitespace; `git diff --check` passed. |

Local static audits used Node `v24.20.0` and the already-installed Ajv `8.20.0`; no dependencies or lockfiles changed. Commands from the repository root were:

```powershell
# Reproducible message-shape check: run the PowerShell block in AGENT_PROTOCOL.md.
node target/protocol-review/message-sequence-audit.cjs
node target/protocol-review/framing-audit.cjs
node target/protocol-review/documentation-audit.cjs
git diff --check
```

The Node audit scripts are temporary review helpers under ignored `target/`, not committed test runners or durable evidence. Their checks are recorded here; future Java/Python suites must implement the conformance gates against the committed fixtures. The initial PowerShell example piped an empty-array fixture away; using `ConvertTo-Json -InputObject` fixed the example, and the published block passed all 90 cases. A local `python --version` probe found an inaccessible WindowsApps alias; the static audit used available Node instead. Python runtime selection remains Step 4.

**Limitations at Step 3 completion:** These checks established contract/fixture consistency. No Java/Python codec, subprocess supervisor, HTTP bridge, or database integration for the new protocol existed, so runtime acceptance, semantic validators, cancellation, history races, and transaction outcomes were not tested. Backend/frontend suites were not rerun for that specification-only step; the 153 backend passes above were the earlier Step 2 results. Steps 4–9 retained their executable gates. Step 1's baseline/branch decisions were still pending, and no migration commit or PR was created during that step. The subsequent merged-HEAD refresh below supersedes its checkout and test-baseline facts.

### 2026-10-16 Merged-HEAD baseline refreshed

Inspected HEAD `92290ae`, its merge parents, frontend scripts/test layout/CI, shared service validators, plan date guard, and HTTP binding advice. Updated current-state facts and Step 1/6 instructions in this tracker and corrected the protocol's food/date/plan boundary descriptions. Earlier observations remain in the dated Step 2/3 records. The protocol wire shapes and fixtures are unchanged: malformed domain values still pass transport shape validation and are rejected by Java services.

The agent-test fix separated shape rejection from shared-service validation. Reproducing the old test gave one failure for `{}` because the service mock did not validate missing fields. The corrected agent/tool/plan suites passed 46 cases. A full backend run against `92290ae` plus the then-uncommitted fix passed **261 tests**, zero failures/errors/skips; `BUILD SUCCESS` at 14:46:19 EDT. That fix was subsequently committed in `4ee8cbe`; this paragraph records the earlier local run, not an outstanding working-tree change. It superseded the historical 153-case local baseline. The earlier review's 256-case CI count was reported evidence, not a CI result fetched or rerun during that refresh.

The full run used Java 26, the existing local Maven cache, forked compilation, and Mockito 5.23.0 as an explicit test JVM agent, matching the runner options documented above:

```powershell
mvn -o "-Dmaven.repo.local=$testRepository" '-Dmaven.compiler.fork=true' "-DargLine=$testJvmOptions" test
```

Initialize `$testRepository`, `$testJvmOptions`, and `MAVEN_OPTS` with the earlier reproducible PowerShell block. Output is locally recorded in ignored `target/agent-merged-baseline-test.log`; it is not durable committed evidence. Documentation checks verified 64 relative links/heading anchors and `git diff --check` passed. No frontend execution, remote CI retrieval, Python conformance, packaging, or new migration implementation is claimed.

### 2026-10-16 Committed baseline and passing CI recorded

Verified locally that HEAD is `4ee8cbe71c53a9991c4a8437dbfc83a992f2f2f5`, that the commit contains the `AgentServiceTest` correction, and that the working tree was clean before this documentation update. Updated the current-state block, progress table, next-step guidance, and protocol baseline reference accordingly.

The review reports that [CI run 38950225490](https://github.com/phanmi/tyler_agent/actions/runs/38950225490) passed all **261 backend tests** on this committed revision. This is recorded separately from the 14:46:19 EDT local run above. The CI result was supplied by the reviewer; it was not independently fetched here because the local `gh` command is unavailable. No backend or frontend tests were rerun for this documentation-only correction. Relative links/anchors and diff whitespace were checked.

### 2026-10-16 Step 4 completed

**Source and scope:** Started from clean committed revision `2158f10`. Added the [Python project](../agent-python/README.md), locked dependencies, source/test layout, protocol infrastructure, and Windows packaging prototype. The existing Java agent remains the application implementation. This task did not commit or publish the changes.

**Implementation**

- Pinned CPython 3.12.14, uv 0.13.1, official OpenAI Python SDK 3.31.0, JSON Schema validation, formatter/linter/type checker/test tools, and PyInstaller. `uv.lock` records transitive versions and hashes. The SDK is installed for the later agent loop but is never instantiated by the prototype.
- Both codecs load the same Draft 2020-12 schema. They enforce strict UTF-8/LF framing, size limits, duplicate-key/surrogate rejection, version/shape checks, precise decimal and ID transport, and semantic bounds without domain validation. Maven packages the shared schema as a resource and the shared fixtures as test resources; Java uses `com.networknt:json-schema-validator:1.5.9`.
- Pure Java/Python sequence observers execute the shared correlation, cancellation, stale/duplicate-message, history-ordering, and uncertain-write scenarios. They have no persistence/process effects. Real process supervision and authoritative Java service outcomes remain later gates.
- Added an allowlisted Java failure translator and MockMvc tests using existing advice, preserving validation/authentication/SQL/file/provider/worker/timeout/cancellation/protocol HTTP response contracts. No public endpoint was added.
- The worker flushes `ready`, accepts shutdown/EOF, reports malformed input safely, and returns `WORKER_UNAVAILABLE` for runs. It never contacts a provider, dispatches an application operation, or claims chat success. Diagnostics use stderr and contain no raw frames/credentials.
- The PyInstaller one-folder bundle includes Python, imported runtime dependencies, the schema, and a copy of the global `.mvn/maven.config` release-version source. No independent release version was introduced. Build output and environments are ignored; source, configuration, lockfile, tests, and packaging spec are reviewable repository changes.

**Contract corrections discovered by executable checks**

The old decimal fixture incorrectly rejected `1E+2147483648`. Java 26 accepts that value, and `1.0E+2147483649`, because their resulting `BigDecimal` scale is representable. Corrected the rejected value to `1E+2147483649` and added three accepted scale-boundary fixtures. Python now checks the resulting Java scale rather than restricting the exponent independently. Also tightened token-regex end anchors to reject a final newline and added four rejection fixtures. These preserve the documented grammar/range; no operation, field, or domain rule changed. The shared message set is now **97 cases** (47 shape-valid, 50 shape-invalid); five shape-valid cases require semantic rejection.

**Verification on Windows x64, OS build 10.0.26200.0**

| Check | Result |
|---|---|
| Fresh locked environment | `uv sync --locked --offline` created `target/agent-fresh-env` using the populated cache and pinned interpreter; all seven development-worker smoke/version tests passed there. |
| Python format/lint/types | Ruff format check and lint passed; strict mypy passed for all nine source/test files. |
| Python conformance and subprocess suite | **166 passed**, no failures/skips: all shared messages, frames, sequences, parser redaction, and development plus packaged worker smoke tests. |
| Windows packaging | PyInstaller build succeeded; `dist/tyler-agent-worker/` includes `_internal/python312.dll`. Tests copied the bundle elsewhere and launched it with empty `PATH`, no `PYTHONHOME`/`PYTHONPATH`, no API key, and a different working directory. Readiness/shutdown, EOF, explicit unavailable runs, invalid sessions, and malformed frames passed. |
| Full Java suite | **428 passed**, zero failures/errors/skips; `BUILD SUCCESS` at 16:20:30 EDT. This includes **152** protocol fixture/redaction cases and **15** failure-mapping tests, plus the previous **261** backend tests. |
| Documentation and whitespace | All **75** relative links/heading anchors in the affected guides and standards resolve; all six Step 4 acceptance items have recorded evidence; `git diff --check` passed. |

Reproducible commands are in the [Python project guide](../agent-python/README.md). The local run used the existing offline Maven/Mockito options from the earlier execution record. Equivalent Python commands from `agent-python/` are:

```powershell
uv sync --locked
uv run --locked ruff format --check src tests
uv run --locked ruff check src tests
uv run --locked mypy
uv run --locked pyinstaller --noconfirm --distpath dist --workpath build worker.spec
uv run --locked pytest tests --worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe
```

In this restricted workspace, uv was bootstrapped under `target/agent-bootstrap`, with workspace-local `UV_CACHE_DIR` / `UV_PYTHON_INSTALL_DIR`; pytest used disposable `--basetemp` directories under `target/`. Logs are locally available as `target/java-step4-full-tests.log`, `target/python-step4-package.log`, and `target/python-step4-packaged-tests.log`; generated logs/binaries are not committed evidence.

**Resolved verification issues:** Default sandbox network access prevented dependency downloads; approved downloads populated the workspace cache. uv's first global interpreter-location attempt failed, so the run used `target/python-managed`. The default pytest temporary-directory location was inaccessible, so tests used workspace-local disposable directories. Initial lint/type findings were corrected. The first Java run exposed the inaccurate decimal fixture described above. The new HTTP test harness initially registered catch-all advice first; registering the specific advice before it, as in the existing contract tests, produced the expected mappings. The packaged test command explicitly names `tests` so pytest loads its dedicated-directory command-line option.

**Remaining gates:** The skeleton is not wired into the application's startup or chat API. No actual Java operation/history dispatch, live model loop, real cross-process transaction rollback, parent-death cleanup, installer/clean-machine certification, Electron integration, or CI workflow expansion is claimed. Those remain Steps 5–9. Frontend code, storage data, and business rules were not changed. Step 4's bundle was tested on this Windows host with ambient interpreter lookup removed; it is not a signed production release.

### 2026-10-16 — Step 4 protocol review corrections

**Source:** Follow-up changes on committed `a85f482`. These correct the existing prototype; Step 5 process supervision and service integration remain unimplemented.

**Changes and regression evidence**

- `ProtocolFailures` now selects Java-owned exception messages by validated error code. It discards worker `error.message`, including from causes, so advice cannot echo or log secrets supplied by the worker. Existing exception types, HTTP statuses, and response shapes remain; remote validation/file detail uses the fixed messages documented in the protocol. Local service exception handling is unchanged.
- Both `MessageCodec.decode` implementations and frame readers now stop after framing/shape validation. The session observers and Python receiver check session, direction, semantics, then state/correlation. Outbound encoding still checks semantic values.
- Added eight shared sequence fixtures for overlapping session/direction/value failures and control cases. Both languages inject raw peer bytes through their readers; five applicable cases also run through real development and packaged-worker pipes. All rejected cases assert no dispatch/history write.
- Mapping tests inject synthetic API-key, prompt, history, and file-content text, then verify exception messages/causes, actual HTTP responses, and captured advice logs. Before the implementation fix, four of 15 Java mapping tests failed. The Python precedence run failed 11 of 13 cases before the fix, including three real-pipe misclassifications; both controls on real pipes already returned `INVALID_VALUE`.

**Verification on Windows x64**

| Command/check | Result |
|---|---|
| `uv sync --locked --offline` | Pinned environment synchronized from cached dependencies. |
| `uv run --locked --offline ruff format --check src tests`, `ruff check src tests`, and `mypy` through the same uv command | Formatting/lint passed; strict typing passed for all nine source/test files. |
| `uv run --locked --offline pytest tests` | **173 passed**, including five new development-worker pipe cases. |
| Focused Maven run with `-Dtest=ProtocolContractTest,ProtocolFailuresTest` | **175 passed**. |
| Full Maven `test` run | **436 passed**, zero failures/errors/skips; includes the final positive log-capture assertion. |
| `uv run --locked --offline pyinstaller --noconfirm --distpath dist --workpath build worker.spec` | Windows bundle rebuilt successfully. |
| `uv run --locked --offline pytest tests --worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe` | **184 passed**, including all 11 smoke cases against the rebuilt, relocated bundle with empty `PATH` and no provider credentials. |

Maven used the previously documented offline cache, forked compilation, and explicit Mockito-agent options. uv used the workspace cache/interpreter directories; pytest used disposable `target/pytest-protocol-fix` and `target/pytest-protocol-fix-packaged` temporary directories. Local logs are `target/java-protocol-fix-full.log`, `target/python-protocol-fix.log`, `target/python-protocol-fix-package.log`, and `target/python-protocol-fix-packaged.log`. These are local results, not a new CI result or proof of the deferred supervisor/service/installer gates. No live provider or user database was used.

### 2026-10-17 — Simplify decimal validation

**Source:** `251dda9` plus this follow-up. At the user's request, removed Python's exponent parsing and Java scale-limit emulation. The shared schema accepts plain decimal strings only; Python uses `Decimal(item).copy_abs()` and a single comparison against `2_000_000_000`. Java applies the same absolute-value bound with `BigDecimal`. IDs retain their existing signed-long range; shared services still decide whether a negative domain value is valid.

This deliberately narrows the earlier prototype contract: scientific notation now fails shape validation, and magnitudes above two billion fail semantic validation. It does not change stored data or HTTP domain validation. The protocol guide documents plain-string serialization for future adapters. Prior scale-boundary observations remain historical below their original execution dates.

Replaced the old exponent/scale fixtures with ordinary decimal examples and exact boundary cases. Added rejection cases for a negative value just below the lower bound and positive/negative exponent notation. Both language suites cover the inclusive positive/negative limits, exact fractions, trailing zeros, negative zero, values just outside the bounds, and the existing session/direction rejection precedence.

**Verification:** Full Maven `test` passed **439 tests** with no failures/errors/skips, using the offline cache, forked compiler, and explicit Mockito agent documented earlier. `uv run --locked --offline ruff format --check src tests`, `ruff check src tests`, and `mypy` passed. `uv run --locked --offline pytest tests` passed **176 tests**. Rebuilt with `uv run --locked --offline pyinstaller --noconfirm --distpath dist --workpath build worker.spec`; running pytest with `--worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe` passed **187 tests**, including the relocated Windows bundle. uv used workspace-local cache/interpreter directories, and pytest used disposable `target/pytest-decimal-simplification` / `target/pytest-decimal-packaged` directories. Local logs are `target/java-decimal-simplification.log`, `target/python-decimal-simplification.log`, `target/python-decimal-package.log`, and `target/python-decimal-packaged.log`. No new remote CI result or later migration gate is claimed.

### 2026-10-17 — Explain local Python protocol failures

Added required developer-facing `detail` to `ProtocolError`, keeping `code` unchanged. Decode errors now explain the framing/encoding/syntax failure, including safe JSON line/column positions. Numeric and run validation identify the field and violated limit. Schema diagnostics use schema rule paths and required-field names; worker stderr includes the safe explanation. Raw parser/schema messages and peer values are excluded, and Java public error mapping remains unchanged.

Added 22 diagnostic cases in the dedicated Python test folder, covering framing, serialization, missing/type-invalid fields, numeric/timeout/blank-value rules, incomplete frames, and private data exclusion from messages and formatted tracebacks. Updated existing worker smoke assertions for the richer stderr output. Existing shared fixtures still verify unchanged error codes and rejection order.

**Verification:** Ruff formatting/lint and strict mypy passed for ten files. Development tests passed **198 cases**; after rebuilding with the documented locked PyInstaller command, the full suite including the relocated Windows executable passed **209 cases**. Commands used `uv run --locked --offline`, the documented workspace cache/interpreter locations, and disposable `target/pytest-error-detail` / `target/pytest-error-detail-packaged` directories. Logs: `target/python-error-detail.log`, `target/python-error-detail-package.log`, and `target/python-error-detail-packaged.log`. An initial mypy schema-union typing error was corrected before final verification. No Java runtime change, new CI result, live model call, or later migration gate is claimed.

### 2026-10-17 — Split Python branching by responsibility

Reviewed all Python source and test scripts and the packaging specification. Refactored the functions combining independent decisions:

- `ProtocolSession.accept` now coordinates validation, shared inactive-request guards, and explicit message-handler dispatch. Separate handlers own readiness, shutdown, run, cancellation, operation request/result, completion, and failure. `observe` routes local events to focused handlers; write-outcome bookkeeping is shared without clearing earlier committed effects.
- The codec separates envelope checks, schema-error selection/formatting, run/numeric validation, frame checks, JSON parsing/serialization, and bounded buffer appends. Validation order, exact error codes/details, and limits remain unchanged.
- The worker loop delegates incoming-message validation, dispatch, request registration, and unavailable-run replies. Test fixture construction and semantic-failure assertions were also extracted into focused helpers. Already focused entry points, resource helpers, and packaging setup needed no structural changes.

The shared fixtures remain unchanged by this refactor. Added ten session cases for cancellation/completion ordering, failure status mappings, repeated terminal messages, invalid local events, and shutdown after EOF; added two malformed-type cases and two worker request-identity cases. Tests exercise public behavior rather than asserting private helper calls.

**Verification:** `uv run --locked --offline ruff format --check src tests`, `ruff check src tests`, and `mypy` passed. Development pytest passed **212 tests**. After the documented PyInstaller rebuild, pytest with `--worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe` passed **225 tests**, including all 13 smoke cases against the relocated Windows executable. Full Maven `test` passed **439 tests**, with no failures/errors/skips, using the previously documented offline cache, forked compilation, and explicit Mockito-agent options. Local logs: `target/python-refactor-tests.log`, `target/python-refactor-package.log`, `target/python-refactor-packaged.log`, and `target/java-python-refactor.log`. pytest used disposable `target/pytest-python-refactor` and `target/pytest-python-refactor-packaged` directories. A mypy narrowing issue and a long test-helper docstring were corrected before final checks. No new protocol contract, runtime dependency, live provider call, remote CI result, or later migration gate is claimed.

## Next work and decisions

- Continue from `251dda9` and the numeric, diagnostic, and maintainability follow-ups recorded above. Record later baseline changes and finish Step 1's inventory and frontend verification evidence before runtime integration.
- Next unimplemented roadmap step: Step 5, the Java adapter and real worker process lifecycle.
- Use the pinned Python/uv environment, shared conformance suites, and packaging commands established in Step 4.
- Decide whether to change round-limit/empty-reply behavior separately before asserting Python parity.
- Implement and test the version 1 deadlines, queue bounds, cancellation behavior, and error mappings before enabling the worker lifecycle.
- Keep packaging and rollout gates pending until tested on the supported desktop environment.

## Maintaining this record

For each executed step, add a dated entry with the changed files, behavior/contract decisions, exact commands or reproducible options, results, limitations, and commit/PR reference when available. Record unsuccessful attempts and their resolution when they affect reproducibility. Mark a step complete only after its gate passes; keep deferred work explicit.

Keep the developer guide linked to this tracker rather than duplicating the execution history. Later implementation changes should update both this status and the relevant application documentation.


### 2026-10-17 — Centralize Python protocol validation

**Changes:** Added [validation.py](../agent-python/src/tyler_agent/protocol/validation.py) as the shared owner of schema, version, operation, numeric, session, direction, and frame validation. The codec delegates validation while retaining JSON serialization and framing. Both the worker and session observer use the same received-message validator, preserving shape → session → direction → semantics precedence. Complete and incremental frames share the size check. Existing codec validation entry points and exception imports remain compatible; lifecycle guards stay with their state owners. No domain rules or wire contracts changed.

**Verification:** Locked offline Ruff formatting/lint and strict mypy passed for 13 source/test files. Development pytest passed **218 tests**, including six new shared-validator cases for overlapping failures and both sender directions. Rebuilt the Windows worker with PyInstaller; pytest with `--worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe` passed **231 tests**, including 13 relocated executable smoke cases. Full offline Maven `test` passed **439 tests**, with zero failures/errors/skips, using the previously documented forked compilation and explicit Mockito agent. Logs: `target/python-validation-package.log`, `target/python-validation-packaged.log`, and `target/java-shared-validation.log`. Temporary pytest data stayed under `target/`.

**Limits:** Local evidence only; no live provider, application database, frontend checks, or remote CI run. Step 5 remains next.


### 2026-10-17 — Group reusable Python utilities in a library package

**Changes:** Moved the codec/frame reader and shared validation into `tyler_agent/library/protocol.py` and `tyler_agent/library/validation.py`. Updated production/test imports and documentation links; the Python README now describes the source layout. Worker lifecycle, session state, and application resource lookup remain at the package root. Tests remain in `agent-python/tests/`. At this intermediate step, internal imports used `tyler_agent.library` (superseded by the protocol-package layout below); wire behavior and executable entry points are unchanged.

**Verification:** Locked offline Ruff lint/format checks and strict mypy passed for 14 files. Development pytest passed **218 tests**. Rebuilt the Windows worker with PyInstaller and ran pytest with `--worker-executable dist/tyler-agent-worker/tyler-agent-worker.exe`: **231 tests passed**, including the 13 relocated executable cases. Logs: `target/python-library-package.log` and `target/python-library-packaged.log`. Checked for old module imports and stale source links; none remain. Diff whitespace check passed. Java tests were not rerun for this module-only relocation; the preceding 439-test result remains the latest backend evidence. No live provider or application database was used.


### 2026-10-17 — Name the transport package by responsibility

**Changes:** Renamed the intermediate `library/` package to `protocol/` at the user's request. The codec and validator now live at `tyler_agent.protocol.protocol` and `tyler_agent.protocol.validation`. Updated production/test imports, README layout, and source links. Worker, session, resources, and the executable entry point remain at the root. No behavior or wire-contract changes.

**Verification:** Locked offline Ruff lint/format checks and strict mypy passed for 14 files; development pytest passed **218 tests**. After a PyInstaller rebuild, pytest including the relocated Windows executable passed **231 tests**. Logs: `target/python-protocol-package-build.log` and `target/python-protocol-package-tests.log`. No old library imports remain in production/tests; diff whitespace check passed. Java tests were not rerun for this Python package rename.


### 2026-10-17 — Include Python checks in CI

**Changes:** Added Python setup using `agent-python/.python-version`, pinned uv 0.13.1 installation, `uv sync --locked --group dev`, Ruff formatting/lint, strict mypy, and `uv run --locked pytest tests` to the existing Ubuntu **Build and test** job. The existing PR/push triggers for `main` apply; no path filter or ignored-failure option bypasses Python tests. Existing Java/frontend steps and the check name remain intact. Updated Python development and standards documentation. Windows bundle verification remains a separate check.

**Verification:** Locally ran the locked dependency sync and all four Python checks using the existing offline cache on Windows: formatting/lint and strict mypy passed, and **218 tests passed**. Diff whitespace check passed. This validates the commands locally, not GitHub-hosted Python installation or Ubuntu execution; the next pushed workflow run supplies that evidence. Repository branch-protection settings were not changed.


### 2026-10-18 — Document and simplify the Java/Python session model

**Scope:** The user clarified that one unit test class is required for each Java class in the agent protocol package. This work preserves the existing wire contract, error precedence, public codec/session entry points, and effect decisions. It does not implement the next migration step.

**Changes:** Added [the session guide](AGENT_SESSION.md), linked from the protocol and developer guides, with state/type tables, both sides' reactions, local events, decision fields, and diagrams. Added `MessageValidator` as the shared owner of schema/version/operation, received-message identity/direction, semantic, Unicode, and frame checks. The codec and frame reader delegate wire rules; failure translation uses the same validator. Split `ProtocolSession.accept` and `observe` into focused handlers and retained correlation/lifecycle guards with the session. Pure infrastructure returns safe decisions/errors; logging remains at the owning boundary.

**Tests:** Each of the six production protocol classes now has its own test class. Message and byte fixture coverage moved from `ProtocolContractTest` into `MessageCodecTest` and `FrameReaderTest`; the former still checks all shared sequences. Added direct `MessageValidatorTest`, `ProtocolSessionTest`, and `ProtocolExceptionTest` coverage, extended `ProtocolFailuresTest`, and centralized shared fixture loading under the dedicated Java test directory. Tests include overlapping validation failures, both directions for all eight message types, numeric boundaries, cancellation/completion ordering, invalid local events, late results, write settlement after EOF, history failures without replay, and safe exception mapping.

**Verification:** Focused Maven test selection for all seven protocol test classes passed **309 tests**. Full offline Maven `test` passed **570 tests**, with zero failures/errors/skips. Both runs used the documented cached repository, forked compiler, and explicit Mockito agent; logs are `target/java-protocol-refactor-focused.log` and `target/java-protocol-refactor-all.log`. Locked offline Python Ruff format/lint, strict mypy, and `pytest tests --basetemp ../target/pytest-java-protocol-refactor` passed (**218 tests**). Java and Python exercised the same unchanged contract fixtures. Documentation links, production-class/test-class correspondence, and diff whitespace were checked.

**Limits:** Local tests only; no remote CI result, live provider, or application database use is claimed. Python packaging was not rebuilt because its sources, imports, dependencies, and resources did not change. State observer tests do not establish real process supervision or database settlement; those remain later migration gates.


### 2026-10-18 — Fix Linux CI type checking of Windows pipe setup

**Failure:** The user-provided Ubuntu CI output passed formatting and lint, then reported four mypy `attr-defined` errors for `msvcrt.setmode` and `os.O_BINARY`. Reproduced all four locally with `uv run --locked --offline mypy --platform linux`; the previous Windows-native mypy run did not expose them.

**Changes:** Replaced the startup guard `os.name == "nt"` with `sys.platform == "win32"`, which mypy recognizes when selecting platform-specific branches. Windows still enables binary stdin/stdout; other platforms skip those APIs. CI and developer commands now check `mypy --platform linux` and `mypy --platform win32` independently. No type suppressions or dependency changes were introduced.

**Verification:** Locked offline Ruff formatting/lint and both mypy targets passed for 14 files. Development pytest passed **218 tests**. Rebuilt the Windows worker and ran pytest with its executable: **231 tests passed**, including relocated startup/pipe smoke cases. Logs: `target/python-platform-guard-build.log` and `target/python-platform-guard-tests.log`. Diff whitespace passed. These are local Windows checks with both static targets, not a claim of Linux runtime or remote CI success; the next GitHub run must establish that. Java/frontend tests were not rerun for this focused Python startup fix.
