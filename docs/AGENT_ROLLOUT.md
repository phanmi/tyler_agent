# Python agent rollout and recovery

[Migration tracker](AGENT_MIGRATION.md) | [Development](DEVELOPMENT.md) | [Desktop packaging](DESKTOP_PACKAGING.md)

## Release status

Python is the default in development and packaged builds. Java remains available through explicit configuration for one validated release. There is no automatic fallback or conversation replay.

The prepared rollout release is **0.2.1**, defined in `.mvn/maven.config`, based on committed `e648ea1` (the Python-default rollout) plus the release metadata/documentation changes. See the [release notes](releases/0.2.1.md). This is a local release candidate; it has not been published. Record its final release commit, artifact hashes, deployment date, and release acceptance before completing the rollback window. Preparing or testing the candidate does not itself complete that window.

Prerequisite evidence:

- [CI run 48](https://github.com/phanmi/tyler_agent/actions/runs/39290936729) passed **Build and test** and **Windows packaged smoke** on `b029ce5`. This verifies the earlier baseline; Step 10 is now committed in `e648ea1`. It is not CI evidence for the 0.2.1 candidate.
- On 2026-10-19 the user confirmed successful clean-Windows acceptance and required CI checks. The clean-machine report and required-check configuration attachment are still to be supplied; this is user-attested evidence, not an independently inspected report.
- The migration execution record records local Step 10 verification separately.

## Development and startup

Install the locked Python environment and build the worker as described in [development setup](DEVELOPMENT.md#run-from-source). Java starts the worker and waits for its handshake before publishing the existing backend readiness event. Startup needs no provider key; chat uses the key stored through Settings.

Browser and Electron development launchers use the repository root as Java's working directory. Direct backend launches must run there or configure absolute worker command entries. Packaged execution passes an absolute bundled worker path and never needs installed Python, Java, or a dependency download. Missing/incompatible workers fail startup visibly; choosing Java rollback is an explicit operator action.

## Configuration-and-restart rollback

Use this procedure only while the legacy implementation is retained:

1. Stop new chats and quit the application. Wait for its Java and Python processes to exit. A cancelled or interrupted tool may already have committed; inspect affected records/files before deciding whether to submit a new request. Do not resubmit an uncertain request automatically.
2. Keep the existing workspace and its key, profile, history, SQLite databases, and files. Once all processes have stopped, make a backup before a release change. Do not reset schemas or copy a running SQLite database as a recovery shortcut.
3. In the shell used to launch the app, set `$env:AGENT_IMPLEMENTATION = 'java'`. For a direct backend command, `--agent.implementation=java` also works. Restart from that shell with the same workspace configuration. Start only one application against that workspace.
4. Confirm the application is ready, history/calendar records and IDs remain, and new requests work. Java mode must not create a Python worker or bridge. Old in-flight requests are not queued for replay on restart.
5. To return to Python, stop again, clear the override with `Remove-Item Env:AGENT_IMPLEMENTATION` (or set it to `python`), and restart. Confirm Python readiness and the same persisted data.

`AgentRollbackIntegrationTest` exercises Python → Java → Python using real temporary SQLite/history and a deterministic provider. It covers both a successful food write and a committed food write followed by provider failure, preserves saved plan IDs and history, executes a new legacy request, and checks that startup does not replay earlier work. This is a rollback exercise, not evidence of a completed release window or a live-provider test.

## Retirement inventory and exit gate

Keep this inventory frozen during the rollback window. New agent functionality belongs in Python.

| Retire after the window | Known callers / required migration |
|---|---|
| `service/agent/AgentService`, `IClientFactory`, `OpenAIClientFactory` | Explicit Java Spring selection; characterization/factory tests; configuration and rollback tests. Keep `IAgentService`, which the HTTP adapter still needs. |
| `tool/ITool` and six Java tool implementations | Legacy agent injection; tests under `src/test/java/org/tyler/tool`; `DomainValidationContractTest`, `FoodRecordSpringWiringTest`, and `PythonToolContractTest` parity assertions. Migrate useful assertions to Python/bridge/shared-service tests before deleting legacy-only fixtures. |
| `com.openai:openai-java` in `pom.xml` | Legacy factory/agent/tools and their SDK response fixtures. The retained Python supervisor/bridge must not acquire SDK dependencies. Verify the dependency tree after removal. |
| `agent.implementation` and conditional rollback wiring | Application YAML, Python/bridge/handler conditions, legacy components, wiring/rollback tests, packaging/recovery guides. Make Python unconditional only when rollback is retired. |
| Temporary architecture allowlist and active legacy documentation | Remove the provider allowlist with the implementation; keep dated migration observations. Update API/tool contract references to retained service/Python sources. |

Before deleting anything, rerun a production/test/documentation caller inventory (`rg` for `org.tyler.tool`, `AgentService`, `IClientFactory`, `OpenAIClientFactory`, `com.openai`, and `agent.implementation`). Distinguish the retained `IAgentService`/`PythonAgentService` from the legacy implementation. The inventory above is not authorization to delete shared controllers, services, validators, models, or tests that still protect them.

Retirement requires a recorded validated release and rollback exercise, then full Java/Python/frontend checks and a rebuilt packaged smoke after removal. The compiled dependency check freezes Java provider imports to the existing rollback types; the Python architecture test rejects direct imports of common database drivers. These checks supplement review; they do not prove the absence of dynamic imports or all possible persistence access.

After retirement, replace the configuration rollback procedure with installing a previously validated release against compatible data, after stopping processes and backing up the workspace. Record that release and compatibility evidence before removing the selector. An unavailable legacy selector must never remain the documented recovery path.
