# Backend coding and design standards

[Developer guide](DEVELOPMENT.md)

This is an editable baseline for backend development in Tyler Agent. It records conventions grounded in the current code and gives guidance for new work. Existing exceptions are identified below; this document does not authorize a broad refactor.

**Status:** Editable baseline. Sections 1–9 describe the standards for new and changed code. Section 10 provides space for project-specific additions.

## 1. Scope and change discipline

- Keep changes focused on the issue and its acceptance criteria.
- Preserve HTTP routes, JSON fields, status codes, tool names, argument schemas, and result formats unless a contract change is explicitly part of the issue.
- Treat stored data formats and SQL schemas as compatibility boundaries. Plan migrations and recovery before changing them.
- Do not combine a layer removal with model, schema, plan-generation, or frontend redesign.
- Update documentation when behavior or architecture changes. Describe verification and any remaining limitations in the pull request.
- Use English for code comments, documentation, exception messages, logs, and user-facing backend messages.

## 2. Responsibilities and dependency direction

For new application features, use this default dependency direction:

```text
HTTP controller / agent tool -> service interface -> DAO interface -> storage
```

| Component                      | Responsibility                                                                                                                 |
|--------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| Controller                     | Bind HTTP input, delegate a use case, and map its result to an HTTP response.                                                  |
| Tool                           | Define the model-facing contract, parse arguments, delegate a use case, and serialize the result.                              |
| Service                        | Own business decisions and coordinate operations for a use case.                                                               |
| DAL                            | DAL class acts as an optional layer inbetween Services and DAO when accessing DAO requires a complex logic of how to get data. |
| DAO                            | Load SQL, bind parameters, map rows, validate persistence input, and translate storage failures.                               |
| Model                          | Represent application data and enforce intrinsic invariants where appropriate.                                                 |
| Exception handler              | Translate exceptions into consistent HTTP responses and log failures.                                                          |
| Configuration / infrastructure | Wire dependencies and provide startup, file access, and request-tracing support.                                               |

Controllers and tools must use the same feature service interface for shared application operations. They must not inject or instantiate DAO/DAL interfaces or implementations, or obtain them through a service locator. Services must not depend on controllers or tools. DAOs must not depend on services, HTTP types, or model-facing tool schemas.

For food operations, the boundary is `FoodController` / `RecordFoodTool` -> `IFoodService` -> `IFoodRecordDAO`. `FoodService` owns the former food DAL behavior and calls the DAO directly. Do not add a forwarding DAL beneath it. The primary food cache remains the DAO implementation selected by Spring and delegates to SQLite.

For workout CRUD and plan persistence, `WorkoutController` and `GenerateWorkoutPlanTool` use `IWorkoutService`, which delegates to `IWorkoutDAO`. Tools must use this service for persistence reads, writes, and failure cleanup as well as normal execution.

An optional DAL beneath a service requires a documented responsibility involving complex data access. It must not duplicate the service API merely to forward calls. Shared application rules belong in the service; SQL and storage exception translation belong in the DAO.

### Existing exceptions and refactor scope

Both food callers use `IFoodService`, and both workout callers use `IWorkoutService` for persistence. `ServiceBoundaryTest` checks compiled production controller and tool classes for forbidden storage references, including method bodies and generic signatures, without adding a dependency.

Workout plan generation, preview, and compensating cleanup remain in the tool, and the controller still uses the tool for previews. Extracting that logic and consolidating validation are separate issues. The food migration preserved the existing validation and behavior in `FoodService`, with tool and DAO validation kept in place. Do not tighten validation, change error behavior, or introduce new transaction semantics as part of a dependency-only refactor.

Do not introduce a DAL that only forwards the same methods to a DAO. For example, the workout service already calls `IWorkoutDAO` directly. Keep a service boundary even when its initial implementation is simple if it defines the application use case shared by callers.


## 3. Packages, naming, and formatting

- Keep production code under `src/main/java/org/tyler` and tests under `src/test/java/org/tyler`.
- Group controllers, services, DAOs, models, and tools by feature within their existing layer packages.
- Match the naming of the package being edited. Existing names include `workout`, `foodrecord`, `userInfo`, and `workoutPlanTool`; do not rename them incidentally. Prefer lowercase package names for new features.
- Use `PascalCase` for types, `camelCase` for fields and methods, and `UPPER_SNAKE_CASE` for constants.
- Follow the existing `I` prefix for service and DAO interfaces, such as `IWorkoutService` and `IWorkoutDAO`.
- Use four spaces for indentation and the surrounding file's brace, import, and wrapping style. Avoid unrelated formatting changes.
- Keep methods focused on one responsibility. Extract a helper when it gives a meaningful name to a repeated operation or complex decision.
- Write Javadoc for public contracts whose validation, absence, side effects, or failure behavior is not obvious. Comments should explain reasons and constraints.
- All SQL query to be written and saved inside the resource folder through a .sql file, no sql query should exists inside DAO itself.

## 4. Object-oriented design and Spring wiring

Apply the four object-oriented principles pragmatically:

| Principle     | Application in this codebase                                                                                                                   |
|---------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| Encapsulation | Keep dependencies and mutable state private; return defensive copies of cached collections.                                                    |
| Abstraction   | Expose use cases and persistence operations through small, meaningful interfaces.                                                              |
| Inheritance   | Use it only for a genuine substitutable relationship or framework extension; do not create base classes merely to satisfy a design checklist.  |
| Polymorphism  | Let callers use interfaces while Spring selects implementations, as with the food cache and SQLite DAO.                                        |

- Prefer composition for shared behavior and decorators for capabilities such as caching.
- Use constructor injection and `private final` dependency fields. Avoid field injection and service locators.
- Use the appropriate Spring stereotype for the role, following neighboring implementations.
- Inject an interface where callers should be independent of implementation details.
- A decorator may explicitly depend on its underlying implementation to avoid injecting itself. `FoodRecordDAOCache` is `@Primary` and delegates to `FoodRecordDAOSqlite`.
- Verify Spring wiring when multiple implementations exist. Do not add another primary bean for the same contract without resolving the ambiguity.
- Treat Spring singleton state as shared across requests. Make mutable state safe for concurrent access and document its ownership.

## 5. Models, validation, and absence

- Prefer records for immutable data carriers, consistent with `Workout` and other models. A record containing a mutable collection still needs defensive copying where isolation matters.
- Validate intrinsic invariants at construction when appropriate; validate request syntax at the boundary and persistence requirements before executing SQL.
- Service entry points must also work correctly for tool callers. Do not rely solely on HTTP validation for business rules.
- Use `BigDecimal` for stored decimal quantities. Preserve the current decimal-string storage convention; avoid conversion through `double`.
- Preserve existing date and repetition contracts: workout dates use ISO `YYYY-MM-DD`, and repetitions use positive sets and repetitions separated by uppercase `X`, such as `4X12`.
- Keep invalid input, missing records, and infrastructure failure distinct.
- Preserve workout DAO/service absence semantics: `Optional.empty()` for an absent lookup and `false` for an update or delete of a missing positive ID. A nonpositive ID is invalid input.
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

The current workout plan tool compensates for failure by attempting to delete newly inserted records. It also reuses matching date/name entries to preserve manual edits. Keep those semantics until an issue explicitly changes them. This approach does not guarantee concurrent uniqueness or transactional rollback.

For a new atomic use case, establish an actual transaction boundary backed by the same data source and transaction manager used by the DAO. Do not assume adding `@Transactional` is sufficient when DAOs construct their own data sources. Verify rollback against a real temporary database.

## 7. Exceptions and HTTP error handling

Choose the exception by the operation's intent, not the JDBC method name:

| Failure | Exception / behavior |
|---|---|
| Database select/read fails | `SQLReadException` |
| SQL resource cannot be read | `SQLReadException` |
| Insert, update, delete, or schema initialization fails | `SQLPersistentException` |
| Insert returns an ID through a query API but fails | `SQLPersistentException`; this is still a write |
| Persistence input fails existing validation | `SQLDataValidationException` |
| Invalid argument or model invariant | Preserve the relevant `IllegalArgumentException` contract |
| Valid lookup finds no record | Return the documented absence result; do not turn it into a database failure |

- Catch specific infrastructure exceptions such as Spring's `DataAccessException`; avoid broad catches that hide programming errors or relabel input validation as a database failure.
- Include a useful operation description and retain the original cause when wrapping a failure.
- Do not return empty collections, `false`, or apparent success to conceal database failures.
- Keep SQL exception-to-HTTP mapping in `SqlExceptionHandler`. Preserve the current generic HTTP 500 response for storage failures and HTTP 400 handling for invalid input.
- Controllers map missing-record results to the existing HTTP contract, including workout update/delete HTTP 404 responses.
- Keep internal SQL, stack traces, credentials, and sensitive paths out of public error responses.

## 8. Tools, configuration, and logging

### Agent tools

- Implement `ITool` and keep the definition, runtime argument validation, and execution behavior consistent.
- Validate model-provided arguments before side effects. Reject unsupported values explicitly.
- Keep preview operations free of writes. Make saving behavior clear in the tool description and result.
- Preserve repeat-call behavior and user edits where the tool contract already supports them.
- Do not add network, filesystem, or database effects that are unrelated to the tool's purpose.

### Configuration and local access

- Use existing Spring configuration properties and defaults; document newly introduced settings in the developer guide.
- Preserve loopback binding and dynamic backend port discovery. Do not hardcode port 8080 in callers.
- Loopback binding and CORS do not authenticate other programs on the same computer. Any authentication requirement needs an explicit design.
- Use the existing sandbox for file access and keep secrets out of source control and test fixtures.
- Keep the shared release version in `.mvn/maven.config`; do not introduce a second backend version source.

### Logging

- Use SLF4J and parameterized messages, with operation context that helps diagnose a failure.
- Preserve request correlation through the existing `RequestIdFilter` and MDC cleanup.
- Prefer one diagnostic stack trace at the handling boundary; avoid logging and rethrowing the same error at every layer.
- Never add logs containing API keys or credentials. Avoid raw chat, profile, or tool payloads; log safe metadata instead.
- Existing DEBUG payload logging should be reviewed in a separate issue.

## 9. Testing and review

Use JUnit Jupiter and the existing Spring Boot test and mocking facilities. Follow neighboring tests; prefer tests of observable behavior over assertions about internal forwarding alone.

| Change | Expected coverage |
|---|---|
| DAO / persistence | CRUD, missing records, invalid input, decimal round trips, and correct read/write exception types with retained causes. |
| Service | Use-case results, failure propagation, and preservation of existing records after rejected changes. |
| Controller | Request/response shape, status codes, invalid input, and missing records. |
| Tool | Function definition, argument validation, output, saving, preview without writes, repeated execution, and failure cleanup where applicable. |
| Cache / wiring | Correct implementation selection, invalidation, failed writes, and isolation of returned collections. |

- Use `@TempDir` and temporary SQLite databases for persistence tests. Never use the real application workspace or user database.
- Mock external model calls; backend tests should not need a live API key or paid network requests.
- Use focused tests during implementation. Run `mvn test` before submitting backend behavior changes; use `mvn clean test` after deleting or moving classes to remove stale compiled types.
- When removing an abstraction, search production code, tests, and documentation for the removed types and package names.
- Maintain an automated dependency check covering production controllers and tools: none may depend on types in `org.tyler.dao` or `org.tyler.dal`. Test fixtures may construct real DAOs for integration tests. Pair the check with service wiring and behavioral tests; interface names alone do not prove correct routing.
- Report commands and results accurately. If a check cannot run, state the limitation.
- Documentation-only changes need link/content checks, not new Java tests.

### Pull request checklist

- [ ] Scope and acceptance criteria are satisfied.
- [ ] Dependencies follow the intended boundaries, or an existing exception is explained.
- [ ] Controllers and tools share the feature service interface and have no DAO/DAL dependencies.
- [ ] No forwarding DAL is retained beneath `FoodService`; food cache selection remains intact.
- [ ] Public contracts and persisted data remain compatible.
- [ ] SQL resources, exception mapping, cache behavior, and transaction semantics are correct where affected.
- [ ] Relevant success, missing-record, invalid-input, and failure tests pass.
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
