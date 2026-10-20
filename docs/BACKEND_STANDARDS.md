# Backend coding and design standards

[Developer guide](DEVELOPMENT.md) · [Python agent standards](PYTHON_AGENT_STANDARDS.md)

This is an editable baseline for backend development and review. It defines rules by architectural responsibility, without assigning behavior to particular application classes. Feature contracts and current implementation details belong in feature documentation and issue acceptance criteria. This document does not authorize a broad refactor.

**Status:** Editable baseline. Sections 1–9 describe the standards for new and changed code. Section 10 provides space for project-specific additions.

## 1. Scope and change discipline

- Keep changes focused on the issue and its acceptance criteria.
- Preserve HTTP routes, JSON fields, status codes, tool names, argument schemas, and result formats unless a contract change is explicitly part of the issue.
- Treat stored data formats and SQL schemas as compatibility boundaries. Plan migrations and recovery before changing them.
- Do not combine a layer removal with unrelated model, schema, business-logic, or frontend redesign.
- Update documentation when behavior or architecture changes. Describe verification and any remaining limitations in the pull request.
- Use English for code comments, documentation, exception messages, logs, and user-facing backend messages.

## 2. Responsibilities and dependency direction

For new application features, use these dependency directions:

```text
Java HTTP controller -> Java service interface -> Java DAO interface -> storage
Python agent tool -> operation protocol -> Java bridge -> Java service interface
```

| Component                      | Responsibility                                                                                                                 |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| Controller                     | Bind HTTP input, delegate a use case, and map its result to an HTTP response.                                                  |
| Python agent                   | Own model/provider integration, prompts and context assembly, the agent loop, and model-response handling.                     |
| Python tool adapter            | Define the model-facing contract, parse arguments, request a shared use case through the bridge, and serialize the result.      |
| Java operation bridge          | Validate protocol input, dispatch allowlisted operations to services, and translate results and failures across the boundary.   |
| Service                        | Own business decisions and coordinate operations for a use case.                                                               |
| DAL                            | DAL class acts as an optional layer inbetween Services and DAO when accessing DAO requires a complex logic of how to get data. |
| DAO                            | Load SQL, bind parameters, map rows, validate persistence input, and translate storage failures.                               |
| Model                          | Represent application data and enforce intrinsic invariants where appropriate.                                                 |
| Exception handler              | Translate exceptions into consistent HTTP responses and log failures.                                                          |
| Configuration / infrastructure | Wire dependencies and provide startup, file access, and request-tracing support.                                               |

Controllers and tools must reach the same feature service interface for shared application operations: Java controllers call it directly, and Python tools call it through the Java operation bridge. Controllers, tools, and bridge handlers must not inject or instantiate DAO/DAL interfaces or implementations, or obtain them through a service locator. Domain services must not depend on controllers, tools, or the worker protocol. DAOs must not depend on services, HTTP types, or model-facing tool schemas.

Use the shared service boundary for persistence reads, writes, and failure cleanup as well as normal execution. Preserve the intended persistence implementation and any configured decorators when changing dependencies.

An optional DAL beneath a service requires a documented responsibility involving complex data access. It must not duplicate the service API merely to forward calls. Shared application rules belong in the service; SQL and storage exception translation belong in the DAO.

### Agent implementation language

- Apply the [Python agent coding and design standards](PYTHON_AGENT_STANDARDS.md) alongside this document for Python agent development and review. They define Python-specific typing, protocol, lifecycle, security, testing, and verification requirements.
- All agent functionality must be written in Python. This includes model/provider SDK integration, prompts, model-facing context assembly, orchestration and tool-call loops, tool definitions, argument parsing and dispatch, and model-facing result serialization.
- Java retains HTTP adapters, worker process lifecycle, protocol transport and operation dispatch, shared domain services and validators, persistence and transactions, sandboxed file access, and persisted settings and history. Reading stored history or credentials for a request does not make Java responsible for constructing model messages or prompts.
- Python tools delegate application operations through the allowlisted Java bridge. They must not access application databases directly, reimplement domain rules or transaction logic, bypass the file sandbox, or create a second owner for persisted settings or history.
- The bridge translates protocol messages and invokes existing service contracts; it must not become another agent implementation. Keep provider types, prompts, model-facing tool schemas, and agent decisions out of new Java code.
- Existing Java agent code is temporary migration and rollback support. Characterization tests, necessary safety or compatibility fixes, and migration cleanup are allowed; new agent features and a parallel Java implementation are not. Record any temporary compatibility change and its retirement condition in the migration log.
- Follow the staged [Java-to-Python agent migration roadmap](AGENT_MIGRATION.md). This policy governs new and changed code; it does not authorize deleting the legacy implementation before the documented acceptance and rollback gates are met.

### Exceptions and refactor scope

Document any existing architectural exception with its reason and scope. Do not extend an exception into new features without an explicit design decision. Use automated dependency checks to protect the intended boundaries.

Preserve existing validation, error behavior, and transaction semantics during a dependency-only refactor. Moving business logic or consolidating validation requires explicit scope and acceptance criteria.

Do not introduce a DAL that only forwards the same methods to a DAO. Keep a service boundary even when its initial implementation is simple if it defines the application use case shared by callers.


## 3. Packages, naming, and formatting

- Keep Java production code under `src/main/java` and Java tests under `src/test/java`, using the project's package namespace. Put Python agent production code under `agent-python/src/tyler_agent/` and tests, fixtures, and test support under the dedicated `agent-python/tests/` directory. Keep shared Java/Python contract fixtures under `contracts/agent/` rather than duplicating them per language.
- Group Java controllers, services, DAOs, and models by feature within their existing layer packages. Group Python agent modules and tool adapters by responsibility and feature; do not add new Java tool packages.
- Match the naming of the package being edited; do not rename packages incidentally. Prefer lowercase package names for new features.
- Use `PascalCase` for types and `UPPER_SNAKE_CASE` for constants. Use `camelCase` for Java fields and methods, and `snake_case` for Python modules, functions, and variables.
- Follow the established Java interface naming convention. Use focused, typed Python contracts where callers need abstraction; do not copy Java naming or inheritance patterns mechanically.
- Use four spaces for indentation and the language's configured formatter, import, and wrapping style. Avoid unrelated formatting changes.
- Keep methods focused on one responsibility. Extract a helper when it gives a meaningful name to a repeated operation or complex decision.
- Write Javadoc for Java public contracts and docstrings for Python public contracts whose validation, absence, side effects, or failure behavior is not obvious. Comments should explain reasons and constraints.
- Before completing a change, read the final affected methods/classes and their surrounding code. Ensure their comments accurately describe their purpose, behavior, side effects, and relevant failure contracts. Add missing explanations and update or remove stale comments, including those outside the changed lines when their meaning is affected. Avoid comments that merely repeat the code.
- Store SQL in resource `.sql` files as described in section 6; do not embed SQL in Java DAOs or Python agent code.

## 4. Object-oriented design and dependency wiring

Apply the four object-oriented principles pragmatically:

| Principle     | Application in this codebase                                                                                                                   |
|---------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| Encapsulation | Keep dependencies and mutable state private; return defensive copies of cached collections.                                                    |
| Abstraction   | Expose use cases and persistence operations through small, meaningful interfaces.                                                              |
| Inheritance   | Use it only for a genuine substitutable relationship or framework extension; do not create base classes merely to satisfy a design checklist.  |
| Polymorphism  | Let callers depend on contracts while dependency injection selects compatible implementations. |

- Prefer composition for shared behavior and decorators for capabilities such as caching.
- Pass dependencies explicitly through constructors or function parameters. In Java, prefer constructor injection and `private final` dependency fields. In Python, avoid hidden global clients or mutable dependency registries. Avoid field injection and service locators.
- Use the appropriate Spring stereotype for Java roles, following neighboring implementations. Wire Python dependencies at the worker's composition boundary.
- Inject an interface where callers should be independent of implementation details.
- A decorator may explicitly select its underlying implementation to avoid injecting itself. Make implementation selection unambiguous.
- Verify Spring wiring when multiple implementations exist. Do not add another primary bean for the same contract without resolving the ambiguity.
- Treat Spring singleton state and long-lived Python worker state as shared across requests. Make mutable state safe for concurrent access and document its ownership. Keep per-request history, credentials, pending calls, and cancellation state isolated.

## 5. Models, validation, and absence

- Prefer Java records or appropriate typed Python data carriers for immutable values. A frozen outer object containing a mutable collection still needs defensive copying where isolation matters.
- Validate intrinsic invariants at construction when appropriate; validate request syntax at the boundary and persistence requirements before executing SQL.
- Java service entry points must also work correctly for Python tool callers through the bridge. Validate JSON and protocol shape at their adapter boundaries and enforce shared domain rules in services or reusable validators. Do not rely solely on HTTP or Python tool validation for business rules.
- Use precise decimal representations for quantities that require exact arithmetic. Preserve the storage precision and format; avoid conversion through floating-point types that lose precision.
- Define and preserve accepted formats, units, ranges, and normalization rules in each data contract.
- Keep invalid input, missing records, and infrastructure failure distinct.
- Define absence and identifier-validation semantics in each operation's contract. Preserve those semantics across callers and storage implementations.
- Validate before mutation. Rejected updates must leave existing data unchanged.

## 6. SQL, persistence, and caching

- Store all SQL statements in `.sql` files under `src/main/resources/db/<feature>/`, including table and index creation. Java DAO code may contain resource paths and loaded statement fields, but no inline SQL statements.
- Load resources from the classpath with an explicit encoding. Fail clearly if a required resource is missing or unreadable.
- Bind values through JDBC parameters. Never concatenate caller input into SQL.
- Keep JDBC details and row mapping inside persistence implementations. Do not return `ResultSet`, JDBC connections, or SQL strings to services.
- Resolve application data paths through the existing file sandbox abstraction. Do not hardcode developer-machine paths.
- Wrap each database operation in exception translation, including schema initialization. Catch the relevant database failure, preserve its cause, and apply the mapping in section 7.
- Preserve ordering when it is part of observable behavior; do not substitute an unordered collection casually.
- Write durable storage before updating a cache. On failure, preserve or invalidate cached state according to the operation's contract; never expose an unpersisted success.
- Cover cache invalidation and defensive-copy behavior when changing cached persistence.

### Operations involving multiple writes

State whether an operation is atomic, partially successful, or uses compensating cleanup. A loop of successful individual writes is not automatically a transaction.

Preserve documented retry, duplicate-handling, and user-edit behavior. Compensating cleanup can itself fail; it does not guarantee transactional rollback. Enforce required uniqueness and concurrency guarantees at a suitable boundary and test them explicitly.

For a new atomic use case, establish an actual transaction boundary backed by the same data source and transaction manager used by the DAO. Do not assume adding `@Transactional` is sufficient when DAOs construct their own data sources. Verify rollback against a real temporary database.

## 7. Exceptions and HTTP error handling

Choose the exception by the operation's intent, not the JDBC method name:

| Failure | Exception / behavior |
|---|---|
| Database select/read fails | Use the project's designated read-failure exception. |
| SQL resource cannot be read | Use the designated resource/read-failure exception. |
| Insert, update, delete, or schema initialization fails | Use the designated persistence/write-failure exception. |
| Insert returns an ID through a query API but fails | Classify it as a write failure. |
| Persistence input fails existing validation | Use the designated validation exception. |
| Invalid argument or model invariant | Preserve the documented argument-validation contract. |
| Valid lookup finds no record | Return the documented absence result; do not turn it into a database failure |

- Catch the relevant infrastructure exception types; avoid broad catches that hide programming errors or relabel input validation as a database failure.
- Include a useful operation description and retain the original cause when wrapping a failure.
- Do not return empty collections, `false`, or apparent success to conceal database failures.
- Centralize exception-to-HTTP mapping in the application's error-handling layer. Preserve the documented response status and error shape for infrastructure and validation failures.
- Map missing-record results to the operation's documented HTTP contract.
- Keep internal SQL, stack traces, credentials, and sensitive paths out of public error responses.
- Preserve failure categories across the worker protocol. Domain validation, storage, provider, and worker failures must remain distinguishable; map them to the established HTTP contract at the Java boundary. Do not serialize arbitrary exception objects or convert infrastructure failures into apparent tool success.

## 8. Tools, configuration, and logging

### Agent tools

- Implement agent tools in Python and keep the definition, runtime argument parsing, and execution behavior consistent. Use the shared operation protocol to reach Java services; keep provider-specific SDK objects inside the Python agent boundary.
- Validate model-provided arguments before side effects. Reject unsupported values explicitly.
- Keep preview operations free of writes. Make saving behavior clear in the tool description and result.
- Preserve repeat-call behavior and user edits where the tool contract already supports them.
- Do not add network, filesystem, or database effects that are unrelated to the tool's purpose.
- Treat an interrupted operation with an unknown write outcome as uncertain, not failed. Do not automatically replay writes, retry a whole conversation, or switch to the legacy agent for an in-flight request unless duplicate execution is explicitly safe and tested.

### Worker protocol and lifecycle

- Use the versioned private process protocol defined by the migration roadmap; the current contract is [agent protocol version 1](AGENT_PROTOCOL.md), with shared schemas and conformance fixtures. Reserve worker stdout for protocol messages and send diagnostic logs to stderr; do not introduce a Python listener as an incidental implementation choice.
- Validate message shape, protocol version, operation allowlists, and request/call correlation before executing side effects. Bound message sizes, queues, and waits, and reject messages for inactive requests.
- Java owns worker startup, readiness, cancellation, and shutdown. Make cleanup idempotent and test failure paths without leaving orphan processes or mixing requests.
- Preserve decimal precision, date formats, identifiers, null semantics, and error categories across serialization. A transport failure must not erase the distinction between a rejected operation and an operation whose result was lost.
- Keep credentials out of process arguments and logs. Use the private protocol for request-scoped credentials and preserve the configured settings and history ownership.

### Configuration and local access

- Use existing Spring configuration properties and defaults for Java. Define and validate Python worker configuration at startup, with explicit ownership and precedence; do not introduce competing defaults for shared settings. Document newly introduced settings in the developer guide.
- Preserve the documented network binding and endpoint-discovery behavior. Read configurable addresses and ports from configuration or discovery results.
- Loopback binding and CORS do not authenticate other programs on the same computer. Any authentication requirement needs an explicit design.
- Use the existing sandbox for file access and keep secrets out of source control and test fixtures.
- Use the project's shared release-version source; do not introduce competing version definitions.
- When changing an implementation default, test omitted, explicit, and invalid selections, including startup failure without silent fallback. Keep dormant migration components inactive unless explicitly selected. Preserve any documented release rollback window, record restart/data-compatibility evidence, and inventory callers before retirement; passing local tests does not complete a release window.

### Logging

- Use SLF4J in Java and the configured Python logging facility in the worker, with parameterized messages and operation context that helps diagnose a failure. Worker logs must go to stderr so they cannot corrupt the protocol stream.
- Review the final affected execution paths for useful diagnostic logging. Logs should identify the operation, its outcome, and safe context such as a record ID or affected count; include the exception cause at the handling boundary when an unexpected failure occurs. Add or improve logs where this information is missing.
- Choose levels deliberately: DEBUG for diagnostic detail, INFO for meaningful completed operations, WARN for recoverable unexpected conditions, and ERROR for failed operations requiring attention. Emit success only after the operation succeeds, and distinguish failed cleanup from the original failure. Avoid noisy method-entry/exit logs that add no diagnostic value.
- Preserve request and operation correlation across Java, Python, and bridge messages through the configured tracing mechanism. Clear thread-local or asynchronous diagnostic context when processing completes.
- Prefer one diagnostic stack trace at the handling boundary; avoid logging and rethrowing the same error at every layer.
- Never add logs containing API keys or credentials. Avoid raw sensitive request or response payloads; log safe metadata instead.

## 9. Testing and review

Use JUnit Jupiter and the existing Spring Boot test and mocking facilities for Java. Use the Python project's configured test runner and mocking facilities for agent code, with tests in `agent-python/tests/`. Follow neighboring tests; prefer tests of observable behavior over assertions about internal forwarding alone. Tests for the temporary Java agent remain valid until its documented retirement gate.

| Change | Expected coverage |
|---|---|
| DAO / persistence | CRUD, missing records, invalid input, decimal round trips, and correct read/write exception types with retained causes. |
| Service | Use-case results, failure propagation, and preservation of existing records after rejected changes. |
| Controller | Request/response shape, status codes, invalid input, and missing records. |
| Tool | Function definition, argument validation, output, saving, preview without writes, repeated execution, and failure cleanup where applicable. |
| Python agent | Model requests, response chaining, tool-call ordering and limits, result extraction, cancellation, and classified provider/tool failures with a fake model provider. |
| Worker protocol / Java bridge | Real subprocess communication, schema and version checks, request isolation, service delegation, failure translation, startup/shutdown, and uncertain write outcomes. |
| Cache / wiring | Correct implementation selection, invalidation, failed writes, and isolation of returned collections. |

- Use temporary directories and isolated test databases for persistence tests, matching the relevant storage behavior. Never use the real application workspace or user database.
- Mock external service calls in unit tests; they should not need live credentials or paid network requests.
- Use focused tests during implementation. Run `mvn test` for Java backend behavior changes; use `mvn clean test` after deleting or moving Java classes to remove stale compiled types. For Python changes, run the project's documented lint, type, and test commands with locked dependencies. Run both suites and cross-process integration tests when changing shared contracts or execution paths.
- When removing an abstraction, search production code, tests, and documentation for the removed types and package names.
- Maintain automated dependency checks for Java controllers and bridge handlers and for Python tools: application operations must reach shared services, without direct DAO/DAL or database dependencies at those boundaries. Test fixtures may construct real DAOs for integration tests. Pair checks with service wiring, protocol, and behavioral tests; interface names alone do not prove correct routing.
- Verify real database rollback and preservation of existing records through the Python-to-Java path when agent operations affect atomic persistence. Mocked bridge replies alone do not establish transaction correctness.
- Report commands and results accurately. If a check cannot run, state the limitation.
- Documentation-only changes need link/content checks, not new runtime tests.

### Required final review workflow

1. Read the final affected functions/classes in context, including surrounding comments and logging. Confirm that comments match the implemented behavior and that logs would help diagnose relevant failures without exposing sensitive data or duplicating stack traces across layers.
2. Inspect the corresponding unit tests against each meaningful behavior and failure path. Cover success, invalid input, missing records, and dependency failures where applicable. Passing tests alone do not establish complete coverage.
3. For each expected exception path, trigger the real method or function under test and assert the specific exception type using the language's test facilities (for example, `assertThrows` / `assertThrowsExactly` in Java or `pytest.raises` in Python). Check the exact type when it is part of the contract. Do not use a broad `Exception` assertion or catch an exception in the test without an assertion. Verify retained causes when wrapping exceptions and messages when they are part of the public contract. Across process boundaries, assert the classified protocol failure and its final observable mapping.
4. When production code catches an exception, trigger that failure and assert the resulting behavior: translated exception, HTTP status/body, documented fallback, or cleanup. A check that nothing was thrown is insufficient. Verify relevant side effects, including unchanged records/cache after rejected writes and attempted compensation after partial failure.
5. Update tests whenever thrown or caught exception behavior changes, run the affected checks, and report any remaining coverage or verification gaps. Keep coverage focused on the affected contract; do not add catch blocks solely to make tests pass.

### Pull request checklist

- [ ] Scope and acceptance criteria are satisfied.
- [ ] Dependencies follow the intended boundaries, or an existing exception is explained.
- [ ] All new agent functionality is in Python; Java changes stay within application services, infrastructure, or documented temporary legacy maintenance.
- [ ] Controllers and Python tools reach the same feature service interface, directly or through the Java bridge, and have no DAO/DAL dependencies.
- [ ] Python does not duplicate domain rules, persistence, transactions, or ownership of settings/history; bridge handlers preserve the service boundary.
- [ ] Worker protocol, lifecycle, request isolation, credential handling, and uncertain write behavior are verified where affected.
- [ ] No redundant forwarding layer is introduced; implementation selection and decorators remain correct.
- [ ] Public contracts and persisted data remain compatible.
- [ ] SQL resources, exception mapping, cache behavior, and transaction semantics are correct where affected.
- [ ] Relevant Java, Python, and cross-process success, missing-record, invalid-input, and failure tests pass.
- [ ] Final affected classes/functions and surrounding comments accurately describe the implemented behavior.
- [ ] Diagnostic logs identify relevant operations, outcomes, and safe context at appropriate levels.
- [ ] Unit tests cover expected thrown exceptions and the observable behavior of caught exceptions, including relevant causes and side effects.
- [ ] Removed types have no remaining references, where applicable.
- [ ] Documentation and verification results are updated.

Use the repository's [pull request template](../.github/pull_request_template.md) when submitting changes.

## 10. Project-specific additions

Add future decisions here, then incorporate stable rules into the relevant section.

```text
Rule:
Reason:
Scope:
Example:
Exceptions or migration needed:
Verification:
```
