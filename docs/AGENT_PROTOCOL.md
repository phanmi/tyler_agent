# Java and Python agent protocol version 1

[Migration tracker](AGENT_MIGRATION.md) | [Backend standards](BACKEND_STANDARDS.md)

This is the Step 3 contract for a Java-owned Python worker. It specifies messages and shared conformance fixtures; it does not implement a worker, bridge, codec, or process supervisor. Step 4 implements and tests codecs in both languages, Step 5 implements lifecycle, and Step 6 implements service handlers.

Compatibility facts were refreshed against merged HEAD `92290ae` on 2026-10-16. Use the [current migration baseline](AGENT_MIGRATION.md#current-implementation-baseline) for service ownership and verification status; earlier checkout observations are retained only in dated execution records.

## Contract artifacts and authority

- [Message schema](../contracts/agent/v1/message.schema.json): closed message and domain-transport shapes, using [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-core).
- [Message fixtures](../contracts/agent/v1/fixtures/messages.json): accepted/rejected shapes and expected values.
- [Framing fixtures](../contracts/agent/v1/fixtures/framing.json): raw bytes, chunk boundaries, malformed JSON, and size boundaries.
- [Sequence fixtures](../contracts/agent/v1/fixtures/sequences.json): directions, correlation, state transitions, cancellation, and uncertain writes.

The schema controls structural validity; this document additionally controls framing, semantic checks, state, and effects. Passing schema validation alone never authorizes an operation. Resolve disagreements between artifacts before changing a consumer. Schema validation must not coerce values, add defaults, or discard unknown properties. No schema references require network resolution.

`protocolVersion` is the integer `1`, independent of the application release version. `ready.payload.workerVersion` reports the shared release version for diagnostics. Version 1 accepts only version 1. New message types, fields, enum members, or changed meanings require a new protocol version and fixture set; no silent downgrade or automatic legacy-agent fallback is allowed.

## Transport and resource bounds

Java launches one worker using redirected private stdin/stdout/stderr, without a shell. Java writes stdin; Python writes stdout. Python opens no HTTP/socket listener. Java generates a fresh 32-character lowercase hexadecimal session identifier per process and supplies it as the non-secret `TYLER_AGENT_SESSION_ID` environment variable. The worker echoes it on every message. It identifies a process generation, not an authentication token.

Each frame is one JSON object encoded in strict UTF-8 followed by LF (`0A`). Writers must flush after each frame. Use escaped `\n`/`\r` inside strings; never pretty-print an envelope across lines. Readers accept LF only, reject BOM, blank frames, CRLF, invalid UTF-8, unpaired Unicode surrogates, duplicate object keys, non-JSON numbers such as `NaN`/`Infinity`, and trailing content after the object. JSON string escapes are decoded once. Reading may split anywhere, including inside a multibyte character; buffer bytes until LF before decoding. EOF with buffered bytes is a truncated frame, never a partial message to execute.

| Bound | Version 1 behavior |
|---|---|
| Maximum frame | 16,777,216 bytes before LF, inclusive, measured on UTF-8 bytes rather than characters. Abort as soon as an incomplete frame exceeds this bound. |
| Startup readiness | 30 seconds, including the worker handshake inside the existing overall backend-startup deadline. |
| Active runs | One per worker; Java queues at most 16 waiting chats. An excess chat fails locally with `WORKER_BUSY` and never reaches Python. |
| Queued wait | At most 30 seconds; expiry fails locally with `DEADLINE_EXCEEDED` without dispatch. Read the current key/history only after the request leaves the queue. |
| Run timeout | `run.payload.timeoutMs`, required integer 1,000–3,600,000 ms; Java default 300,000 ms. |
| Operation timeout | `run.payload.operationTimeoutMs`, required integer 1,000–3,600,000 ms and no greater than `timeoutMs`; Java default 60,000 ms. Each operation is also bounded by remaining run time. |
| Cancel/shutdown grace | 5 seconds before Java terminates a nonresponsive worker; termination does not prove a Java operation rolled back. |
| Worker recycling | Java retires an idle worker after 10,000 accepted runs, bounding retained request-ID tombstones. A new worker gets a new session ID. |

These are new transport/lifecycle bounds, not new domain limits. Previously unbounded large files/results may exceed the frame limit. Reject oversize outbound input before dispatch; fail oversize output without truncating it or claiming a completed write was undone. Record this operational limit during rollout. No chunked large-message extension exists in version 1. Configuration may change run/operation deadlines within their ranges; it cannot silently change framing or queue limits.

Use monotonic timers locally. Java starts the run timer when it sends `run`; Python starts its timer on receipt. Java's earlier deadline remains authoritative. Deadlines never reset for a new tool round or result. Continuously drain stderr with bounded buffers so logging cannot block protocol progress.

## Envelope and payloads

Every message has exactly `protocolVersion`, `type`, `sessionId`, `requestId`, and `payload`. All five are required. `requestId` is explicitly null for `ready`/`shutdown`, otherwise a nonempty correlation string matching `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. Java allocates request IDs and never reuses one in a session. Python allocates operation IDs with that syntax, unique within the request. An operation ID is independent of a database ID and an OpenAI call ID; Python retains the mapping to the original provider call ID.

All payload fields listed below are required; no additional fields are permitted. Nullable domain fields are explicitly identified in the schema. Absent and null are not interchangeable in the envelope.

| Type | Direction | Payload | Meaning |
|---|---|---|---|
| `ready` | Python to Java | `workerVersion: string` | First and only readiness message, before a run. It requires no key or provider access. |
| `run` | Java to Python | `message: string`, `history: [{role, content}]`, `model: string`, `apiKey: string`, `maxToolRounds: 5`, `timeoutMs`, `operationTimeoutMs` | Begin one chat using a credential/configuration snapshot. History roles are `user` or `assistant`; content/message are strings. Model/key must be nonempty and nonblank. |
| `operation_request` | Python to Java | `operationId`, `operation`, `input` | Request one allowlisted Java service operation; schema depends on the operation. |
| `operation_result` | Java to Python | `operationId`, `operation`, `outcome`, and exactly `result` for `success` or `error` for `failure` | Resolve the pending operation once. Error results are not model-visible successful tool outputs. |
| `completed` | Python to Java | `reply: string` | Successful agent execution. Empty replies are allowed by the characterized baseline. |
| `failed` | Python to Java | `error` | Terminal unsuccessful execution. Must not append a successful history exchange. |
| `cancel` | Java to Python | `reason: user_cancelled | deadline_exceeded | shutdown` | Request cancellation of the active run, never a rollback instruction. |
| `shutdown` | Java to Python | `reason: application_exit | startup_cancelled | protocol_error | worker_recycle` | Stop accepting work and exit. There is no shutdown reply; process exit is the acknowledgement. |

Java validates a missing/blank API key before sending `run`, preserving the existing authentication error. Request body validation and trimming remain at the HTTP adapter. An empty `message` is structurally valid because the service transport does not add an HTTP validation rule. The worker constructs provider messages from history and the current message. Java must not construct provider-specific objects or send prompts/tool schemas in this protocol.

## Allowed Java operations and data representations

Operation names are private service commands. Public model-facing tool names and schemas remain owned by Python. The bridge decodes these domain transport objects and delegates to the existing service or sandbox boundary; it does not execute a Java tool or parse raw model arguments.

| Operation | Public tool | Input | Success result | Model-visible output produced by Python |
|---|---|---|---|---|
| `food.insert` | `recordFood` | `{food}` | `{id, food}` | Pretty-printed food JSON string, excluding the generated ID. |
| `workout_plan.generate_save` | `generateWorkoutPlan` | `{startDate, goal, equipment}` | `{plan}` | Pretty-printed plan JSON string. |
| `user_info.read` | `GetUserInfo` | `{}` | `{profile}` | Existing Java-record-style text, not JSON. |
| `file.read` | `readFile` | `{path}` | `{content}` | Raw content string. |
| `file.write` | `writeFile` | `{path, content}` | `{path}` | `Wrote ` followed by the requested path. |
| `time.current` | `getCurrentTime` | `{}` | `{text}` | Server-local time text in the existing `yyyy-MM-dd HH:mm:ss z` representation. |

The schema defines food, plan, day, workout, and profile fields. Profile transport uses the stored JSON names `User`, `Other`, `Name`, `Gender`, `Age`, `JobType`, `Info`, and `expectationFromLLM`. Python renders the legacy result in record-component order:

```text
UserInfo[user=User[name=..., gender=..., age=..., jobType=...], other=Other[info=..., expectationFromLLM=...]]
```

Null components render as `null` (for example `UserInfo[user=null, other=null]`), with strings unquoted and unescaped in this legacy text result. Decimal age rendering must match the supplied Java `BigDecimal.toString()` token; do not normalize it through a float. This compatibility representation is not a prompt assembled by Java.

### Decimal values and identity

Food quantities/nutrition, workout weights, and profile age cross the pipe as decimal **strings**, or null where allowed. The lexical grammar is `-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`. Parse with `BigDecimal`/`Decimal`, never binary floating point. Keep significant digits, trailing zeros/scale, exponent value, and sign according to Java `BigDecimal` semantics; Java should encode its `BigDecimal.toString()` representation. In particular, Java normalizes negative zero (`-0.00` becomes `0.00`); Python must match that normalization in returned model-visible output. Negative values are transport-valid and remain subject to existing domain validation. The transport does not introduce rounding, units, or new numeric ranges.

Before dispatch, decimal tokens must be representable by Java `BigDecimal`, including its scale range; the schema's lexical pattern alone cannot establish that. Preserve previously accepted model input coercions in the Python adapter before encoding a domain command. Python emits JSON **numbers**, not quoted decimals, in the model-visible food/plan JSON string, using decimal-aware serialization. JSON results are compared by field names, value types, exact numeric value/scale where observable, and preserved nulls; whitespace is not a new business contract.

The generated food `id` is a decimal string representing `1` through `9223372036854775807`. Leading zeros, signs, zero, and more than 19 digits fail the schema (`INVALID_MESSAGE`). A lexically valid value above the signed-long maximum fails the codec's full-range check (`INVALID_VALUE`). Do not convert IDs through a floating-point JSON parser. Public tool output still omits this ID. Version 1 introduces no ID-based mutation command.

### Dates, nulls, and existing adapter behavior

- Dates remain lossless strings. Do not apply JSON Schema `format: date` or a four-digit-year regex, normalize time zones, or pass them through Python's limited calendar range in the codec. Java services use `DomainValidation.parseDate` for real ISO calendar dates without trimming. Expanded ISO years such as `+10000-01-01` can be valid inputs. Food insertion, lookup, and date deletion require valid dates; nonblank text alone is insufficient.
- Required transport fields may be explicit null where the schema permits it. Preserve plan-day `activity: null`, empty workout arrays, and nullable profile fields. Empty strings are not automatically null.
- Shape-valid malformed dates, missing domain values represented as null, and negative nutrition must reach the appropriate domain/adapter validation outcome, not be mislabeled as corrupt transport.
- `FoodService` already invokes the shared `FoodValidator` for required fields, dates, and nonnegative quantities/nutrition before persistence. `RecordFoodTool` owns JSON deserialization and result serialization. Step 6 must call the same service from the bridge and preserve its validation errors; Python and bridge handlers must not duplicate these domain rules.
- File tools currently coerce scalar path/content through `JsonNode.asText()` and wrap malformed/missing arguments as file exceptions. No-argument tools ignore arguments. Python adapter parity tests must preserve or explicitly document changes to these behaviors. Transport `path`/`content` are strings after adapter parsing/coercion.
- Preserve unknown-tool and adapter failure distinctions; do not turn all adapter errors into HTTP 400. Missing/null plan text fields pass from the tool to shared service validation. `WorkoutPlanService` rejects start dates after `LocalDate.MAX.minusDays(6)` with `IllegalArgumentException("startDate must allow seven representable calendar days")`. This existing domain guard remains in Java; a date outside that bound is transport-valid, then domain-invalid.

## State, correlation, and execution

Process states are `STARTING`, `IDLE`, `RUNNING`, `CANCELLING`, `STOPPING`, and `CLOSED`. Java owns the authoritative active request and operation ledger. Python mirrors the conversation state. State changes and operation dispatch decisions must be serialized locally; the protocol reader itself must remain able to process cancellation and responses.

1. A new worker starts in `STARTING`. Accept exactly one matching-session/version `ready`, then become `IDLE`. A `run` before readiness is `INVALID_SEQUENCE`.
2. Accept `run` only while `IDLE`, with a fresh request ID. Become `RUNNING`. A second run is a protocol violation; excess user requests are handled by Java's queue before this point.
3. Accept `operation_request` only for the active request while `RUNNING`, with a new operation ID and no pending operation. Register it before dispatch. Validate the complete frame, shape, semantics, allowlist, correlation, and current cancellation state before invoking any service.
4. Execute calls sequentially, even when a provider response contains multiple calls. After the matching `operation_result`, clear the pending operation. A successful result permits another operation; a failure requires terminal `failed` and prohibits further operations for that run.
5. `operation_result` must match the pending request ID, operation ID, and operation name. Results for an unknown operation in the active run are `OPERATION_NOT_PENDING`. Repeated operation requests/results are `DUPLICATE_MESSAGE`; never dispatch or apply them twice.
6. Accept `completed` only for the active request with no pending operation, no cancellation, and no earlier failure. Record one worker terminal outcome, then keep the lane `RUNNING` while Java attempts its single history append. No new operations or runs are allowed during that append. Only after history settles does Java return to `IDLE` and send HTTP success or history failure; neither outcome replays tools. Accept `failed` with no pending operation, or while cancelling to acknowledge cancellation. An independently observed Python deadline may also emit `failed` with `DEADLINE_EXCEEDED` while a Java operation is pending; this enters `CANCELLING` and retains the ledger. No failure frame cancels a still-executing Java service. A failed run returns to `IDLE` only once pending Java work settles.
7. Do not start another run until all previously dispatched Java operations have settled, even if Python has exited or the caller timed out. If an operation cannot be confirmed settled, quarantine the conversation lane and fail queued/new chats with `WORKER_UNAVAILABLE`; keep the UI's other Java services available. Killing Python does not kill a Java transaction or prove rollback.

Retain request tombstones for the session and operation tombstones for the active run. A repeated `run` ID is fatal `DUPLICATE_MESSAGE`. A duplicate terminal message for a finished request is ignored with that reason; other well-formed late worker messages for inactive requests are ignored as `REQUEST_NOT_ACTIVE`. Neither may append history or change a newer request. Unknown future request IDs are also inactive. Outbound Java messages must never target an inactive request, except an already-sent message racing with terminal completion.

Wrong direction, wrong session, reused active operation IDs, malformed frames, incompatible versions, unknown operations, and other invalid active-state transitions are fatal protocol errors: no rejected operation is executed; terminate/quarantine that worker, fail an active run locally, and never replay it. Schema/byte validation precedes inactive-message handling. A valid late result after cancellation can update Java's retained operation ledger, but cannot resume Python tool execution or turn the cancelled HTTP call into success.

### Cancellation, shutdown, and race ordering

Java records cancellation first and atomically disables new operation dispatch, then sends `cancel`. Python stops starting tools/provider continuations and emits `failed` with `REQUEST_CANCELLED` or `DEADLINE_EXCEEDED`. Repeated cancellation of the same active request is idempotent. A cancel crossing an already-sent terminal message is ignored by Python; Java's local event order decides the HTTP outcome. If cancellation was recorded before completion acceptance, later `completed` is discarded as `REQUEST_NOT_ACTIVE`. A well-formed `operation_request` arriving while `CANCELLING` is also ignored as `REQUEST_NOT_ACTIVE`, without dispatch: it may have been sent before Python saw cancellation. Once Java accepts completion and begins history persistence, completion owns the terminal outcome; cancellation does not interrupt or undo that append. After the cancellation terminal and all Java operations settle, return to `IDLE` unless shutdown/exit has begun.

An already dispatched Java write may finish or fail. Do not label it rolled back because a cancel, deadline, EOF, or worker exit occurred. Observe the service outcome or keep it unknown. Cancellation does not interrupt a database transaction merely to meet a worker shutdown timer. A timeout covers waiting for a result, not proof that the operation stopped.

`shutdown` is accepted in any nonclosed state, once or repeatedly. It disables new runs/operations, cancels an active run if any, closes provider resources, and exits within the grace period. No terminal frame is required on shutdown; Java resolves any active call locally. Worker EOF/parent death must trigger the same cleanup even while a provider request is pending. Parent-death detection, bounded termination, and actual Windows process cleanup are Step 5/9 tests.

## Failures and HTTP compatibility

`error` contains exactly `category`, `code`, `message`, and `writeOutcome`. `message` is a safe nonempty diagnostic string, without keys, raw prompts, tool payloads, SQL, paths containing sensitive information, or stack traces. No exception class name is loaded dynamically from the protocol. Java maps allowlisted codes explicitly and retains original local exceptions/causes in its request ledger when available; Python preserves category/code when forwarding an operation failure.

`writeOutcome` describes the operation for an `operation_result`, and all tool operations for a terminal `failed`:

- `none`: no tool write committed or remains unresolved. Validation rejection before dispatch or confirmed transaction rollback qualifies; a generic thrown write exception alone does not.
- `committed`: at least one write is confirmed committed, with no unresolved dispatched write. A later failure does not undo it.
- `unknown`: a dispatched write may have committed, partially written, or still be running and its final effect is unconfirmed. This takes precedence over `committed`/`none`.

Java computes the effective outcome from its own ledger, never downgrading uncertainty based only on a worker claim. Successful `food.insert`, `workout_plan.generate_save`, and `file.write` confirm committed effects; a plan result can reuse existing records without new writes, but remains conservatively classified as completed write-capable work. Reading operations do not add write uncertainty. A rejected protocol message is never dispatched; earlier writes still count. These states are diagnostic, not exactly-once delivery or concurrent deduplication guarantees.

| Category | Codes | Java mapping and HTTP behavior |
|---|---|---|
| `validation` | `INVALID_ARGUMENT`, `SQL_DATA_VALIDATION` | `IllegalArgumentException` / `SQLDataValidationException`; HTTP 400 with the designated safe validation message. |
| `authentication` | `API_KEY_MISSING`, `PROVIDER_AUTH` | `OpenAIKeyException`; HTTP 401. Preserve `OpenAI API key is empty; chat is unavailable` and `This API key is invalid or unavailable`, respectively. Provider unauthorized and permission-denied failures both use the latter. |
| `provider` | `PROVIDER_FAILURE` | Unexpected provider/transport/rate-limit/server failure; HTTP 500 with `Internal server error. Please try again later`. Preserve the existing HTTP mapping, not the provider's 429/5xx status. |
| `storage` | `SQL_READ`, `SQL_WRITE`, `SQL_ACCESS` | Existing SQL read/write/Spring data-access mapping; HTTP 500 with `Database operation failed. Please try again later`. |
| `storage` | `FILE_READ`, `FILE_WRITE` | Existing file exceptions; HTTP 500 with `Failed to read file: ` / `Failed to write file: ` plus the designated safe message. The payload message must not include that prefix twice. |
| `worker` | `UNKNOWN_TOOL`, `SERIALIZATION_FAILURE`, `HISTORY_FAILURE`, `WORKER_UNAVAILABLE`, `WORKER_BUSY`, `INTERNAL_FAILURE` | Existing unexpected-failure HTTP 500 mapping. `HISTORY_FAILURE` is synthesized by Java after worker completion, not emitted by Python. |
| `timeout` | `DEADLINE_EXCEEDED` | Java adapter failure, HTTP 500 with the existing generic internal-error body if the connection remains open. No 504 contract change in this migration. |
| `cancelled` | `REQUEST_CANCELLED` | Java adapter failure with the same HTTP 500 body if a response can still be sent; disconnected clients receive no response. No new public cancellation endpoint or 499 status. |
| `protocol` | `INVALID_FRAME`, `MESSAGE_TOO_LARGE`, `INVALID_MESSAGE`, `UNSUPPORTED_VERSION`, `UNKNOWN_OPERATION`, `WRONG_DIRECTION`, `SESSION_MISMATCH`, `REQUEST_NOT_ACTIVE`, `OPERATION_NOT_PENDING`, `DUPLICATE_MESSAGE`, `INVALID_SEQUENCE`, `INVALID_VALUE` | Internal transport/programming failure, never domain validation. Active failures use HTTP 500 with the generic internal-error body; ignored stale/duplicate terminal events produce no second response. |

Every HTTP error body retains `{ "error": "..." }`, as defined by the existing Java exception handler contract. No protocol fields are added to public responses. Preserve actual existing file-adapter parsing errors as `FILE_READ`/`FILE_WRITE` (500); sandbox argument rejection remains `INVALID_ARGUMENT` (400). Existing Java exceptions remain authoritative for a locally failed operation. Do not attempt to reconstruct arbitrary remote exception stacks or claim original Java throwable identity across the process boundary.

The bridge emits only domain/SQL/file/internal failures for its operations. Python emits provider/authentication/adapter/internal failures or forwards an operation failure unchanged, aggregating `writeOutcome` without reducing uncertainty. Startup/frame/protocol failures without a safe active request ID are handled locally and close the worker; do not invent a request ID to send `failed`.

### Rejection precedence

First enforce byte count (`MESSAGE_TOO_LARGE`), framing/strict decoding/JSON rules (`INVALID_FRAME`), then an explicitly present incompatible `protocolVersion` (`UNSUPPORTED_VERSION`). An unknown string operation on an operation message is `UNKNOWN_OPERATION`; other schema failures are `INVALID_MESSAGE`. Then check session, direction, semantic values (ID range, nonblank model/key, timeout relationship, representable decimals), and state/correlation. Semantic value failures are `INVALID_VALUE`. A blank credential from a valid Java request is caught locally as `API_KEY_MISSING`, before encoding; a blank credential arriving on the private wire is a sender defect. Multiple simultaneous violations are diagnosed by this order. Domain validation runs only after protocol acceptance.

## Credentials, logging, and history

- Only Java reads/stores the API key. Send its current snapshot in `run.payload.apiKey` through private pipes; never environment variables, command arguments, fixture data, or logs. Fixture keys are conspicuous synthetic values and must never be sent to a provider.
- Keep credentials and conversation data scoped to the run. Drop references on terminal cleanup and dispose of clients when replaced or the worker exits; do not promise memory zeroization in managed runtimes.
- Keep prompts, history, food/profile data, file contents, and raw frames out of logs at all levels, including parser errors. Log only type, safe IDs, code/category, duration, size, and effect state. Log exception details at one owning boundary with redaction.
- Java must redact/drain worker stderr without forwarding arbitrary content directly to the renderer. Do not merge stdout and stderr, persist frame dumps, or treat logging as protocol messages.
- Java retains history normalization and persistence. `completed` does not itself mean history is durable. Failed/cancelled calls do not append exchanges; a history failure after tool writes does not retry them. Clearing history and simultaneous chat requests must be serialized at the Java history ownership boundary so older completion cannot repopulate a cleared conversation unintentionally; the chosen queue/history policy must be tested in Step 5/8.

## Fixture use and later executable checks

Message fixtures are individual JSON objects with `expected.schemaValid` and a reason for rejection. `expected.semantic` is separate: a negative nutrition or a malformed date can be structurally/transport-valid but subject to domain rejection later. Accepted values must survive encode/decode without coercion; compare decimals/IDs as exact strings in the transport.

The shared fixtures live under `contracts/agent/v1/fixtures/` so both languages consume the same test data. They are contract examples, not production data or an alternative implementation of plan templates. Language-specific test code remains in the dedicated testing directories. To check message shapes from the repository root with PowerShell 7's `Test-Json`:

```powershell
$schemaPath = (Resolve-Path 'contracts/agent/v1/message.schema.json').Path
$messageCases = (Get-Content 'contracts/agent/v1/fixtures/messages.json' -Raw | ConvertFrom-Json -Depth 100).cases
foreach ($case in $messageCases) {
    $json = ConvertTo-Json -InputObject $case.message -Depth 100 -Compress
    $actual = Test-Json -Json $json -SchemaFile $schemaPath -ErrorAction SilentlyContinue
    if ($actual -ne $case.expected.schemaValid) {
        throw "Unexpected schema result: $($case.id)"
    }
}
Write-Output "Verified $($messageCases.Count) message-shape expectations"
```

This checks structural acceptance only. Semantic rejection reasons, framing, direction, state, HTTP mapping, and effects need the separate checks below; a green schema run does not establish worker conformance.

Framing fixtures provide `chunksBase64` or a bounded generator descriptor. Concatenate decoded chunks for static inspection, but feed them separately to a real incremental reader in Step 4. A `sized_completed` generator starts from its supplied empty-reply `message`, compact-encodes JSON in UTF-8, then fills `payload.reply` with ASCII `x` until the pre-LF frame has exactly `frameBytes` bytes; append LF. Expected frame acceptance does not assert that its request is active. This avoids committing multi-megabyte files.

Sequence fixtures refer to message-case IDs through `ref`. An optional `set` map replaces existing fields at JSON Pointer paths; it is a fixture instruction, never a wire message. A `from` field identifies direction. Local events (`deadline`, `operation_settled`, `history_settled`, `pipe_eof`, `cancel_grace_elapsed`) model supervisor observations. Each step lists expected acceptance/rejection/ignore behavior and the resulting Java supervisor state. `historyAppend` counts attempts caused by that step; `dispatchCount` and `replayCount` count cumulative service dispatches and replays within the case. `operation_settled` records a local outcome after the worker no longer awaits a result; it must not send a late result back to resume tools. These fixtures specify an oracle; static inspection does not prove a live supervisor implements it.

Step 4 must add parameterized Java and Python tests loading these same fixtures, without duplicating fixture data:

1. Validate message shape and semantic rules, preserving decimal strings, full-range IDs, expanded dates, explicit nulls, multiline text, and Unicode.
2. Exercise incremental byte readers with split UTF-8, combined frames, malformed/truncated input, duplicate keys, invalid versions, and exactly-at/over-size boundaries.
3. Validate error-code/category pairs and rejection precedence. Neither schema engine may silently coerce types or remove fields.
4. Check fixture direction/sequence expectations in a pure protocol-state harness. Step 5/8 then connect those expectations to real process/service execution, timing, cancellation, and actual database state.
5. Verify the documented HTTP mapping at the Java boundary, including legacy file errors and worker/provider failures. No live API keys or provider calls are needed.

Steps 4, 5, and 8 remain incomplete until their respective executable gates pass. Step 3 verification is limited to contract consistency, schema/fixture checks, and documentation validation.
