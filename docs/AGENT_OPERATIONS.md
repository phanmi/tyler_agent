# Agent service operation bridge

[Migration tracker](AGENT_MIGRATION.md) | [Protocol contract](AGENT_PROTOCOL.md) | [Session lifecycle](AGENT_SESSION.md)

Step 6 implements Java's allowlisted service access; Step 7 implements the Python model loop and six tool adapters. The bridge is wired only when `agent.implementation=python`; Java remains the default until the later rollout gate. This page inventories the preserved adapter behavior and extension boundaries. The complete HTTP/Python/database integration gate remains Step 8.

## Dispatch and ownership

`PythonWorker` validates framing, session, direction, values, and request/operation state before invoking `IOperationDispatcher` on its callback executor. [ServiceOperationDispatcher](../src/main/java/org/tyler/service/agent/bridge/ServiceOperationDispatcher.java) additionally validates standalone input against the existing operation-request schema through `MessageValidator`, then looks up an `IOperationHandler` in an immutable map. The dispatcher has no feature-service dependencies or operation switch.

[OperationBridgeConfiguration](../src/main/java/org/tyler/config/OperationBridgeConfiguration.java) discovers handler components when `agent.implementation=python` and injects their collection. Each handler declares its case-sensitive protocol operation name; Spring bean names do not determine routing. `MessageValidator` derives the allowed names from the shared protocol schema. Registry construction rejects null handlers, blank names, unapproved names, duplicates, and missing operations before requests can execute. The Java default does not create these handlers or the dispatcher.

| Private operation | Public tool name (case-sensitive) | Destination | Transport result |
|---|---|---|---|
| `food.insert` | `recordFood` | `IFoodService.insertFood` | `{id, food}`; generated ID is a decimal string. |
| `workout_plan.generate_save` | `generateWorkoutPlan` | `IWorkoutPlanService.generateAndSave` | `{plan}` with the existing plan/day/workout fields. |
| `user_info.read` | `GetUserInfo` | `IUserInfoService.get` | `{profile}` with the stored capitalized property names. |
| `file.read` | `readFile` | `IFileSandboxRead.read` | `{content}` without newline or Unicode rewriting. |
| `file.write` | `writeFile` | `IFileSandboxWrite.write` | `{path}` echoing the requested path after successful writing. |
| `time.current` | `getCurrentTime` | Injected `Clock`, wired with the server's default time zone | `{text}` formatted as `yyyy-MM-dd HH:mm:ss z`. |

Domain rules remain in the existing services and validators. Negative domain numbers, null required values represented in the wire schema, and malformed calendar dates reach shared validation. Missing transport properties, incorrect JSON types, unknown operations, unsupported decimal notation/range, and invalid Unicode fail before service invocation. The dispatcher never executes legacy Java tools or constructs model messages.

Decimals use `BigDecimal` and plain decimal strings in transport, preserving fractional precision and trailing zeros without passing through binary floating point. Negative zero is normalized by Java. Existing protocol magnitude limits and signed-long ID limits remain unchanged. SQLite's existing `NUMERIC` affinity can normalize trailing zeros on a later database read; this step changes neither storage types nor persisted data. The immediate food result uses the value returned by the service, including its decimal scale.

The six implementations live in [bridge/handler](../src/main/java/org/tyler/service/agent/bridge/handler): `FoodInsertHandler`, `WorkoutPlanSaveHandler`, `UserInfoReadHandler`, `FileReadHandler`, `FileWriteHandler`, and `CurrentTimeHandler`. Each constructor receives only its required service, sandbox capability, or clock, plus the shared [OperationPayloadMapper](../src/main/java/org/tyler/service/agent/bridge/OperationPayloadMapper.java) where conversion is needed. The mapper owns domain-object decoding and plain-decimal result encoding. Conversion failures retain their causes as internal failures; a serialization failure after a write is never classified as a domain rejection.

## Adding an operation

1. Define the private operation in the shared protocol contract, schema, and fixtures. Coordinate Java/Python compatibility and write-effect classification, including any required protocol-version change. Registering a Java bean alone does not authorize a new wire operation.
2. Add a focused class implementing `IOperationHandler` under `service/agent/bridge/handler`. Annotate it with `@Component` and `@ConditionalOnProperty(name = "agent.implementation", havingValue = "python")`; return the schema's exact operation name from `operationName()`.
3. Inject the required service or sandbox interface through the constructor. Use `OperationPayloadMapper` for shared object conversion. Keep domain rules and transaction orchestration in the service, and preserve original exceptions for the supervisor's cause ledger. Handlers receive input after protocol validation.
4. Add handler tests for delegation, output, and failures. Extend shared protocol fixtures and verify production Spring registration, malformed-input rejection, and the architecture boundary. Add persistence integration coverage when the operation changes stored data.
5. Run the affected checks and update this inventory and the migration record. No dispatcher switch, central service field list, or central handler construction list needs editing.

## Legacy adapter inventory for Step 7

The source links below remain the migration reference for exact descriptions and model-facing schemas. Each Python [builtin tool package](../agent-python/src/tyler_agent/tools/builtin) owns its adapter and `definition.json`; shared [argument parsing](../agent-python/src/tyler_agent/tools/parsing.py) and [result rendering](../agent-python/src/tyler_agent/tools/rendering.py) preserve existing compatibility rules. `PythonToolContractTest` compares all six complete definitions against Java and verifies shared output goldens; Python tests consume the same goldens. Domain validation stays in Java.

### Adding a Python tool

1. Add a package under `agent-python/src/tyler_agent/tools/builtin/<tool_name>/`, containing `__init__.py`, `adapter.py`, and `definition.json`. Keep one tool's name, description, and full model schema in its own definition file.
2. Implement `async def execute(raw: str, operation: Operation) -> str` in `adapter.py`. The injected `operation` is scoped to the current run. Keep handlers free of mutable request state; call Java for domain/storage work and preserve adapter parsing/formatting at this boundary.
3. Add dedicated tests under `agent-python/tests/`. Existing tools retain Java parity checks during migration; new operations also require coordinated protocol/schema/fixture and Java-handler changes. Adding a Python tool cannot expand Java's operation allowlist.
4. Run Python checks and rebuild/smoke-test the worker. Discovery and PyInstaller collect the bundled packages automatically; no central handler map, import list, or combined JSON catalog needs editing.

Discovery reads only the trusted bundled namespace, never a model-supplied module path. Packages are sorted by folder name for deterministic definition order. Missing definitions/adapters, invalid schemas or handler signatures, duplicate names, and an empty catalog fail startup before readiness. The worker validates one catalog and reuses it across requests; each request binds its own Java operation capability, and providers receive defensive copies of definitions. The package `__init__.py` only exports the public facade. `test_tool_registry.py` covers extension, invalid registration, discovery, and isolation; relocated-bundle checks verify dynamic imports and all definition resources.

| Legacy adapter | Parsing behavior before the service operation | Model-visible result |
|---|---|---|
| [RecordFoodTool](../src/main/java/org/tyler/tool/foodRecordTool/RecordFoodTool.java) | Jackson deserializes raw arguments into `Food`, including numeric JSON values and supported decimal strings. Unknown properties/type failures are argument errors. Missing record components/fields deserialize to null and are rejected by the shared food validator. Python must encode valid parsed decimals as plain strings and represent omitted nullable domain fields explicitly as null; it must classify a null top-level food as domain-invalid before creating the object-only transport request. | Jackson-style pretty food JSON with numeric values, excluding the generated ID. |
| [GenerateWorkoutPlanTool](../src/main/java/org/tyler/tool/workoutPlanTool/GenerateWorkoutPlanTool.java) | Requires a JSON object; each supplied non-null start date/goal/equipment must be text. Missing/null fields pass to service validation. The current runtime parser ignores unrelated object properties, although its advertised schema forbids them. The bridge accepts only the three protocol fields; Python must preserve the adapter behavior before constructing that request. | Pretty plan JSON with numeric weights, nullable activities, and empty rest-day workout arrays. |
| [GetUserInfoTool](../src/main/java/org/tyler/tool/userTool/GetUserInfoTool.java) | Ignores arguments. The service owns profile loading, normalization, and existing missing/unreadable-file fallback. A null top-level stored profile fails in the legacy path; the bridge also fails rather than inventing a profile. Nullable components are retained. | Java-record-style `UserInfo[user=User[...], other=Other[...]]`, with component order and null/string rendering specified in the protocol guide. |
| [ReadFileTool](../src/main/java/org/tyler/tool/fileTool/ReadFileTool.java) | Malformed JSON and missing/null/blank path are file-read failures. Uses `JsonNode.asText()` for path coercion; scalar values can become strings. Extra properties are ignored by the runtime parser. Python owns that compatibility parsing; the bridge receives a string and delegates sandbox checks. | Raw file contents. |
| [WriteFileTool](../src/main/java/org/tyler/tool/fileTool/WriteFileTool.java) | Malformed JSON, missing/null/blank path, and missing/null content are file-write failures. Path/content use `JsonNode.asText()`; empty content is allowed. Extra properties are ignored. Python must distinguish those adapter errors from sandbox argument rejection. | `Wrote ` followed by the requested path. |
| [GetCurrentTimeTool](../src/main/java/org/tyler/tool/timeManagementTool/GetCurrentTimeTool.java) | Ignores arguments. Python requests an empty-input operation instead of substituting its own local clock/time zone. | Server-local time text in the existing format. |

No-argument tools send `{}` to Java after their adapter parsing. Public tool names are not accepted as private operation names. Protocol version 1 already excludes scientific notation and decimal magnitudes above two billion; adapters must honor that documented transport restriction rather than round or silently truncate values. Expanded ISO years remain strings through Python and are interpreted by Java. The seven-day upper-date bound belongs to the shared plan service.

## Persistence, failures, and file access

- Food insertion appends through the same service/DAO path used by HTTP. Equal entries retain independent generated IDs; subsequent calendar reads/deletions use those IDs.
- Plan saving uses the existing transaction and data source. Sequential repeats reuse date/name matches, preserve existing IDs and manual edits, and do not imply concurrent deduplication. No new preview operation is exposed; the existing preview service remains read-only.
- The file bridge uses the existing sandbox's normalized workspace-path checks. It does not expand filesystem permissions or add path resolution of its own. Tests cover traversal/absolute paths outside the workspace, blank paths, missing files, and text round trips. This is preservation of existing sandbox behavior, not a new symlink-containment guarantee.
- Local service exceptions propagate unchanged into the supervisor's run ledger. Safe wire errors retain their established domain/SQL/file/internal classification; terminal Java wrappers retain local causes without returning them publicly. Unknown write outcomes remain conservative after failure. Tested transaction rollback does not justify assuming every thrown write exception means no effect.
- Serialization or transport failure after a write never triggers retry, delete compensation, or fallback. Operation success logs contain only the allowlisted name; no food/profile/file content is logged by the bridge.

## Verification boundary

`ServiceOperationDispatcherTest` verifies all six shared result fixtures, exact decimal/ID conversion, tool names and legacy food/profile output compatibility, malformed-input rejection before dependency access, and exception identity. `BridgeValidationTest` runs real food/plan services and compares shared validation errors with the legacy adapters, including missing fields, dates, negative numbers, expanded years, and the last representable week.

`OperationRegistryTest` verifies registration failures, immutable registration, and validation before handler execution. `OperationBridgeConfigurationTest` verifies actual Spring discovery, Python-only wiring, and startup rejection of invalid or incomplete registrations. Each handler has a dedicated unit test; `OperationPayloadMapperTest` covers decimal/null conversion and safe exceptions with retained causes.

`BridgePersistenceTest` uses the production Spring handler configuration, temporary SQLite databases, and the real sandbox/services. It covers a child-JVM worker callback inserting food, independent IDs and deletion, plan rollback after partial inserts, repeated saves preserving edits, and file restrictions. `ServiceBoundaryTest` checks compiled bridge dependencies for DAO/DAL, JDBC, provider SDK, and legacy-tool references, and prevents the dispatcher from depending on feature services, models, or sandbox implementations. `PythonAgentConfigurationTest` proves exactly one real dispatcher is wired to the shared dependency instances.

The full existing service suite still tests read-only preview, rollback without compensating deletes, and duplicate handling. Full HTTP → Python model loop → Java service persistence and interruption parity remain Step 8; a deterministic JVM worker fixture does not establish that later integration gate.

## Python execution and operational limits

[run_agent](../agent-python/src/tyler_agent/agent.py) builds the initial request from Java's supplied history and current message, using the supplied model and all six tool definitions. Calls execute sequentially in response order. Each output uses its original `call_id`, and each continuation uses `previous_response_id`. After five tool rounds, the last response's text/refusals are concatenated without separators; further calls are not executed. Empty output remains an empty successful reply. Java alone appends the successful exchange.

[ResponsesProvider](../agent-python/src/tyler_agent/provider.py) owns SDK conversion and safe error classification. A new asynchronous client belongs to each run and closes after success, failure, or cancellation. It uses the Java credential snapshot, the explicit OpenAI endpoint, zero SDK retries, and a request timeout of the smaller of 60 seconds and the run deadline. No key is read or client created during readiness. Authentication/permission errors become `PROVIDER_AUTH`; other SDK failures become `PROVIDER_FAILURE`, retaining local causes without logging or serializing provider detail. This follows the [Responses function-calling contract](https://developers.openai.com/api/docs/guides/function-calling).

The Python worker does not replay operations or fall back to Java. New operational bounds are 100 function calls and 1,000 output items per provider response, plus the existing 16 MiB text/frame bound and five-round limit. Oversized provider output fails before that response's tools execute. These are explicit resource limits for the opt-in Python implementation. The existing run and operation deadlines also apply.

An operation failure retains Java's validated category, code, and safe message. The terminal `failed` payload replaces the operation's `writeOutcome` with the aggregate for the whole run: a successful write followed by a failed read reports `committed`, even when that read reports `none`. Uncertainty still takes precedence. The original Java cause remains in the supervisor ledger; it is not serialized to Python.

Model arguments must be one complete JSON value; trailing non-whitespace after a value is rejected. This deliberately tightens Jackson's permissive trailing-token behavior for malformed arguments. Model numeric input is parsed exactly; valid scientific numeric arguments are converted to plain wire decimals within the existing two-billion protocol bound. Negative domain numbers, missing domain fields, and invalid calendar dates still reach Java validation. Numeric file path/content coercion uses decimal text without binary-floating-point rounding; ordinary text, booleans, empty content, and container-to-empty-text behavior remain compatible. These edge-case decisions do not change the advertised string-based file schemas or application domain rules.

`test_agent.py`, `test_tools.py`, and `test_provider.py` cover orchestration, adapter parity, actual SDK requests over an in-memory HTTP transport, and disabled retry behavior. `test_runtime.py` controls cancellation, callback ordering, timeouts, and terminal messages. `test_worker_execution.py` exercises the real entry point and pipes with an injected fake provider. Production has no fake-provider environment switch or configurable test endpoint.
