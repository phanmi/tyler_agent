# Python agent coding and design standards

[Developer guide](DEVELOPMENT.md) · [Backend standards](BACKEND_STANDARDS.md) · [Python development guide](../agent-python/README.md) · [PR review agent](../.github/agents/pr-review.agent.md)

AI agents and human contributors MUST follow these standards for new and changed Python agent code. They supplement the backend standards with Python implementation and verification rules. Responsibilities are defined here; feature behavior, concrete contracts, and migration gates belong in their respective documentation.

**MUST** identifies a requirement; **SHOULD** identifies a default that needs a documented reason to depart from. An exception must record the rule ID, reason, affected scope, verification, and retirement condition where temporary. These standards do not authorize unrelated refactoring or implementation of later migration steps. Required behavior is not evidence that the current prototype already implements it; consult the [migration tracker](AGENT_MIGRATION.md) for status and the [protocol contract](AGENT_PROTOCOL.md) for wire requirements.

## PY-01 — Ownership and service boundaries

- All new agent functionality MUST be written in Python: provider integration, prompts and context assembly, model/tool orchestration, model-facing schemas, argument parsing, and result serialization.
- Java owns HTTP adapters, worker supervision, operation dispatch, shared domain services and validators, persistence and transactions, sandboxed file access, and persisted settings/history. Python tools MUST request application operations through the allowlisted Java bridge and shared services.
- Do not access application databases directly, duplicate domain rules or transaction logic, bypass the file sandbox, or introduce competing settings/history storage. Transport parsing must not change which domain inputs the shared service accepts.
- Preserve existing tool names, argument/result formats, HTTP behavior, stored data, and repeat-call semantics unless the issue explicitly changes them. Temporary legacy changes need the migration justification and retirement condition required by the backend standards.

## PY-02 — Layout, naming, and formatting

- Production code belongs in `agent-python/src/tyler_agent/`. Python tests, fixtures, and test helpers MUST live in the dedicated `agent-python/tests/` directory, not beside production modules. Shared Java/Python contract fixtures remain under `contracts/agent/`; do not duplicate them per language.
- Group modules by cohesive responsibility. Separate provider access, orchestration, tool adapters, and transport where their contracts or lifecycles differ. Extract helpers for clarity and reuse, not arbitrary file-size limits.
- Use `snake_case` for modules, functions, and variables; `PascalCase` for types; and `UPPER_SNAKE_CASE` for constants. Follow configured Ruff formatting and import ordering, four-space indentation, and English identifiers, comments, docstrings, errors, and logs.
- Imports MUST NOT start workers, make network calls, load request credentials, or mutate application data. Perform initialization at an explicit entry point. Avoid wildcard imports and unrelated formatting changes.

## PY-03 — Design and dependency injection

- Prefer focused functions and composition. Use classes when they give state, invariants, or resource ownership a clear home. Use `Protocol` or an abstract base class when a substitutable contract has a real consumer; do not copy Java interface prefixes or create forwarding layers mechanically.
- Apply encapsulation, abstraction, inheritance, and polymorphism pragmatically. Inheritance must represent a valid substitutable relationship, not merely share a few methods.
- Pass clients, transports, configuration, clocks, and other dependencies explicitly. Wire them at the composition boundary; avoid mutable global clients, service locators, and hidden dependency registries.
- State the owner and lifetime of mutable state. Avoid mutable default arguments and unintended shared collections. Use immutable data carriers or defensive copies where request isolation requires them; a frozen object does not make nested collections immutable.

## PY-04 — Types, validation, and value semantics

- Keep strict mypy checking enabled for production and test code. Annotate contracts and model optional values explicitly. Isolate unavoidable `Any` at dynamic JSON or third-party boundaries; validate and narrow values before use. Casts are not runtime validation. Suppressions need a narrow scope and an explanation.
- Distinguish provider responses, protocol DTOs, and internal values when their shapes or meanings differ. Validate untrusted shape at the adapter boundary using the established schema facilities. Shared domain validation remains in Java services; do not independently tighten required-field, date, or numeric rules.
- Preserve missing-versus-null semantics, stable identifiers, units, and ordering defined by each contract. Avoid truthiness checks that confuse valid zero or empty values with absence. Remember that Python `bool` is a subtype of `int` when validating numeric input.
- Use exact decimal strings and `Decimal` where the contract requires decimal precision; never round-trip them through `float`. Define precision and rounding for arithmetic explicitly. Preserve the wire contract's full numeric/date range rather than narrowing it to a convenient Python type. Calendar dates must not acquire timezone conversions accidentally.
- Reject malformed protocol values and non-finite numbers as required by the shared contract. Use explicit runtime validation, not `assert`, for external inputs; assertions may be disabled.

## PY-05 — Provider and tool orchestration

- Keep provider SDK types and response handling behind a focused Python boundary. Prompts and model context belong in Python; application rules remain in shared services. Inject the provider so tests can control responses without credentials or network calls.
- Keep tool definitions, argument parsing, dispatch, and result serialization consistent. A schema declaration alone does not validate a model response. Reject malformed or unsupported arguments before requesting side effects.
- Keep each tool's definition and adapter together in a focused module or package. Use a validated registry as the tool set grows; avoid expanding central dispatch branches or a combined schema file. Reject duplicate names and invalid registrations before execution. Discovery must be limited to trusted application packages, preserve deterministic ordering, and work in packaged builds. Shared catalogs must not retain request-specific credentials or operation capabilities.
- Dispatch only registered tools and allowlisted operations. Preserve call identity, response chaining, ordering, and existing duplicate behavior. Never report success or invent a saved record after a failed operation.
- Bound model rounds, tool calls, output sizes, and elapsed time using explicit configuration or documented limits. Define behavior for absent output, malformed arguments, unexpected tool names, and partial failure. Keep read-only operations free of writes.

## PY-06 — Protocol and serialization

- Follow the versioned [protocol contract](AGENT_PROTOCOL.md) and [shared schemas and fixtures](../contracts/agent/). Stdout is exclusively for protocol frames; diagnostics go to stderr. Do not add a Python network listener as an incidental implementation choice.
- Validate framing, encoding, message shape, version, direction, allowlisted operation, correlation, and sequence before dispatch. Do not silently coerce values, add defaults, or drop unsupported fields contrary to the contract.
- Bound frame sizes, buffering, queues, and waits. Handle fragmented input, EOF, malformed frames, and broken pipes explicitly. Serialize writes through a single owner so concurrent output cannot interleave frames.
- Associate messages with the correct session, request, and operation. Reject stale or duplicate messages according to the contract. Preserve nulls, exact values, classified failures, and write outcomes across both languages. Protocol changes require shared fixture and compatibility updates.

## PY-07 — State, concurrency, and lifecycle

- Keep credentials, history, pending calls, cancellation, and completion state scoped to their request. A late result MUST NOT affect another request. Enforce the protocol's supported concurrency rather than assuming all worker state is safe to share.
- Give each task, thread, subprocess, client, and stream an owner responsible for cancellation and cleanup. Use context managers or `try/finally`; cleanup must be idempotent and must not conceal the original failure. Do not leave unobserved background tasks or orphan processes.
- Use asynchronous execution only where needed. Do not block an event loop with synchronous I/O or an unbounded wait. Propagate cancellation after cleanup; do not swallow `CancelledError` or catch `BaseException` as a normal recovery strategy.
- Java supervises startup, readiness, cancellation, and shutdown. Python MUST cooperate with the documented lifecycle, including EOF and lost-parent handling where applicable. Test races between cancellation, completion, and shutdown when changing those paths.
- A timeout, cancelled task, or terminated worker does not prove that an application write rolled back. Preserve the operation outcome reported by Java, including uncertainty.

## PY-08 — Exceptions, retries, and uncertain outcomes

- Keep malformed input, domain rejection, provider failure, storage failure, protocol failure, and worker failure distinguishable. Preserve the established machine-readable categories and final HTTP mapping; do not classify errors by parsing human-readable prose.
- Catch the narrowest useful exception at the boundary that can handle or translate it. Preserve local causes with exception chaining when wrapping; sanitize public/protocol diagnostics. A broad catch is appropriate only at a deliberate top-level failure boundary with tested reporting and cleanup.
- Do not return empty results, fabricated defaults, or apparent success to hide failure. Define which exceptions callers receive and what state remains after an error.
- Make retry policy explicit, including SDK defaults. Retry only demonstrably safe operations with bounded attempts and deadlines. Do not automatically replay a write, an entire conversation, or an in-flight request through another implementation after an unknown outcome. Idempotency or concurrent deduplication claims require implementation and tests.

## PY-09 — Security and privacy

- Treat user text, model output, tool results, files, and provider responses as untrusted data. They cannot expand the operation allowlist, file permissions, or application authority.
- Never evaluate untrusted text as Python, load untrusted pickle data, or interpolate it into shell commands. Use established safe parsers and explicit operation arguments; file access stays behind the Java sandbox.
- Obtain request credentials through the documented private channel. Keep secrets out of source, fixtures, command-line arguments, logs, error payloads, and generated artifacts. Do not persist credentials or conversation content in a second Python store.
- Minimize credential lifetime and avoid copying sensitive request data into long-lived caches or tasks. Do not claim Python object cleanup guarantees memory zeroing.
- Configure new outbound destinations and access deliberately. Do not forward credentials or private content to an unrelated endpoint as an incidental implementation choice.

## PY-10 — Comments and diagnostic logging

- Public contracts with non-obvious validation, absence, side effects, resource ownership, or failure behavior need docstrings. Comments should explain constraints and decisions, not narrate syntax.
- Before finishing, read the final changed functions/classes and surrounding code. Correct stale comments and verify that they reflect actual behavior, including failure and cleanup paths.
- Use the configured `logging` facility with parameterized messages and stderr output. Log safe operation names, correlation identifiers, outcomes, elapsed time, and error codes where helpful. Select levels deliberately and avoid noisy entry/exit logs.
- Never log raw frames, prompts, conversation history, credentials, full provider responses, or parser diagnostics containing input. Review third-party logging configuration too. Exception text and stack traces can contain sensitive payloads; log only safe diagnostics at the handling boundary and avoid repeated traces across layers.
- Report success only after it is confirmed. Distinguish the original failure, cleanup failure, and uncertain write outcome. Test redaction where logs or error construction change.

## PY-11 — Dependencies, configuration, and packaging

- Treat [pyproject.toml](../agent-python/pyproject.toml), [.python-version](../agent-python/.python-version), and [uv.lock](../agent-python/uv.lock) as the runtime/tooling sources of truth. Use the pinned tooling and locked installs. Update dependency declarations and the generated lockfile together; do not hand-edit the lockfile or weaken checks to bypass a failure.
- Add dependencies only for a concrete need. Keep runtime and development dependencies separate. Review compatibility, security, licensing, and bundle impact when introducing or upgrading a dependency.
- Validate configuration at its owning boundary with explicit defaults and precedence. Do not create competing configuration for shared settings. Use the [global release-version source](../.mvn/maven.config); release and protocol compatibility versions have separate meanings.
- Resolve packaged resources independently of the current working directory and developer checkout. Build with the pinned environment and include required runtime, dependencies, schema, and version resources. The installed application must not require the user's Python installation or download dependencies at startup.
- For startup, runtime, dependency, or packaging changes, verify an applicable relocated bundle with a sanitized environment and no provider credentials. Record target-platform limitations; a development interpreter test is not packaged-app evidence.
- When a migration changes the default runtime, packaged acceptance MUST exercise that default without an override that could hide incorrect wiring. Keep rollback explicit, preserve uncertain write outcomes across restarts, and record the validated release before retiring compatibility code. Distinguish local candidate checks, hosted CI, and user-supplied environment evidence.

## PY-12 — Tests and regression coverage

- Put all Python tests and test support in `agent-python/tests/`, with the shared contract-fixture exception in PY-02. Use pytest and the configured type/lint checks for tests too.
- Test observable contracts with a fake provider and controlled dependencies. Unit tests MUST NOT require live credentials, paid model calls, user files, or the application database. Use temporary directories and isolated integration data.
- Use parameterized tests for required fields, malformed shapes, nullability, dates, numeric boundaries, and protocol versions as relevant. Cover meaningful success and failure behavior rather than assertions about private implementation details alone.
- Trigger the real function under test for exception coverage. Use `pytest.raises` with the designated exception type; check the exact type when required, retained causes, stable codes, and relevant side effects. Do not use a broad `Exception` assertion. When an exception is caught, verify the translated failure, cleanup, or documented fallback, not merely that execution returned.
- Control ordering and time for delayed results, cancellation, stale replies, retries, and shutdown races. Avoid arbitrary sleeps. Subprocess tests need bounded waits, stream draining, and cleanup even when assertions fail.
- Run both languages against the same fixtures for shared contracts. Use real subprocess integration for lifecycle and transport claims, and real isolated database integration through the service path for atomicity claims. Mock replies alone cannot establish process cleanup or database rollback.
- Add regression coverage for recurring defects. Update tests when an exception contract changes and check removed APIs for remaining production, test, and documentation references.

## PY-13 — Verification and change review

For Python behavior changes, run these checks from `agent-python/` using the configured toolchain:

```powershell
uv sync --locked
uv run --locked ruff format --check src tests
uv run --locked ruff check src tests
uv run --locked mypy --platform linux
uv run --locked mypy --platform win32
uv run --locked pytest tests
```

Check both supported type-checking targets regardless of the development host. Guard platform-specific APIs with conditions the type checker can narrow, such as `sys.platform`; do not suppress missing attributes to bypass platform errors. Targeted type checking does not establish runtime behavior on another operating system.

Use the [Python development guide](../agent-python/README.md#required-checks) for Java conformance commands and [bundle build and smoke checks](../agent-python/README.md#build-and-smoke-test-the-windows-bundle). Shared contracts or execution changes require relevant Java, Python, and cross-process checks. Packaging changes also require the applicable build and smoke tests. Documentation-only changes need content and link checks, not runtime tests.

The [CI workflow](../.github/workflows/ci.yml) runs locked Python dependency installation, formatting, lint, type checking, and the full development test suite as part of the existing Build and test job. Python test failures MUST fail CI; do not use optional execution or ignored failures to bypass this gate. Packaged-app checks and other migration gates still need their own evidence. Distinguish local evidence from CI results and report actual commands, results, and limitations. Never claim a mocked result or an unexecuted check proves integration behavior.

Before requesting review:

- [ ] Applicable PY rules and backend standards are satisfied; any exception is documented.
- [ ] Agent functionality stays in Python and application operations use the shared service boundary.
- [ ] Types, serialization, validation, and public contracts remain consistent.
- [ ] State ownership, cancellation, resource cleanup, and uncertain outcomes are handled where affected.
- [ ] Comments and safe diagnostic logs describe the final code accurately.
- [ ] Tests cover meaningful success, thrown/caught failures, and relevant ordering or lifecycle risks.
- [ ] Required checks passed, or specific verification limitations are reported.
- [ ] Documentation and the migration execution record are updated when behavior or migration status changes.

## Future additions

Add new rules by responsibility, without assigning duties to a particular application class. Record the rule, reason, scope, compatibility impact, exceptions or migration work, and verification. Use stable rule IDs so issues and the [PR review agent](../.github/agents/pr-review.agent.md) can reference them precisely.
