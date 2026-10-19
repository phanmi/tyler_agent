# Agent service operation bridge

[Migration tracker](AGENT_MIGRATION.md) | [Protocol contract](AGENT_PROTOCOL.md) | [Session lifecycle](AGENT_SESSION.md)

Step 6 implements Java's allowlisted service access. The bridge is wired only when `agent.implementation=python`; Java remains the default until the later rollout gate. Python's model loop and tool adapters are Step 7, so the current worker still rejects valid runs as unavailable. This page inventories the adapter behavior that Step 7 must preserve; it does not claim those Python adapters are implemented.

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

The source links below remain the authority for exact descriptions and model-facing schemas. Step 7 must port those contracts and add Python parity tests; it must not copy domain validation out of Java.

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
