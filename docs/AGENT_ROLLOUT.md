# Python agent rollout and recovery

[Migration tracker](AGENT_MIGRATION.md) | [Development](DEVELOPMENT.md) | [Desktop packaging](DESKTOP_PACKAGING.md)

## Release status

Python is the sole agent implementation in this checkout. Java owns HTTP, worker supervision, the operation bridge, shared services, and persistence. The temporary Java orchestration/tools, provider SDK, and runtime selector were retired after the v0.2.1 validation window. There is no automatic fallback or conversation replay.

The retirement changes are an **unreleased working-tree build** based on `dbc6f1e`, whose tracked tree matches release commit `2e43873`. The global version is still 0.2.1; this task does not publish or replace the existing release. Use a new release version before publishing the retirement build. Generated local artifacts with the same version are not the published recovery artifact.

## Validated recovery release

- Release: [v0.2.1](https://github.com/phanmi/tyler_agent/releases/tag/v0.2.1), commit `2e43873c8c642c68e8645141a2ad45cc9b60f02a`.
- Published: **2026-10-20, 16:18 EDT**.
- Installer: [Tyler-0.2.1.Setup.exe](https://github.com/phanmi/tyler_agent/releases/download/v0.2.1/Tyler-0.2.1.Setup.exe), 296,082,944 bytes.
- SHA-256: `c6dcc8715aac2f76a8043146e7554243d5e554ccd58bfa4b5dcbfb0c0371be37`. GitHub's asset digest matches the local `frontend/out` installer inspected before rebuilding.
- [CI run 53](https://github.com/phanmi/tyler_agent/actions/runs/39481721083) passed for the release commit. [Run 52](https://github.com/phanmi/tyler_agent/actions/runs/39454630874) passed for the source baseline `dbc6f1e`. Neither run verifies the subsequent retirement changes.
- On **2026-10-21**, the user confirmed that this release completed validation including restart/rollback, identifying the installer in `frontend/out`. This is user-attested acceptance; no independent clean-machine report was supplied.
- Before removal, both configuration-and-restart rollback integration cases passed against the baseline, with isolated databases/history and a fake provider. The current restart suite retains committed-write, stable-ID, history, and no-replay coverage. See the migration execution record for post-retirement checks.

The GitHub release description copied earlier candidate notes and contains an outdated candidate hash. Use the published asset digest above. Remote release text was not edited by this task.

## Development and startup

Install the locked Python environment and build the worker as described in [development setup](DEVELOPMENT.md#run-from-source). Java starts the worker and waits for its handshake before announcing backend readiness. Startup needs no provider key; chat uses the key stored through Settings.

Browser and Electron development launchers use the repository root as Java's working directory. Relative JAR and explicit workspace overrides remain anchored to the original launch directory. Direct backend launches must run there or configure absolute worker command entries. Packaged execution supplies an absolute bundled worker path and needs no installed Python or Java. Missing/incompatible workers fail startup visibly.

## Recovery using the previous release

1. Stop new chats and quit the application. Wait for Java and Python to exit. An interrupted operation may already have committed; inspect the affected records/files before deciding to submit a new request. Never automatically replay an uncertain request.
2. Back up the complete workspace after all processes stop: keys, profile, history, SQLite databases, and files. Preserve the configured workspace path and record IDs. Do not reset schemas or copy a running database.
3. Obtain the exact validated v0.2.1 installer linked above and verify its SHA-256. Do not substitute a locally rebuilt file based only on its filename/version. Stop the newer app before installing the recovery release. If Windows requires uninstalling the newer application first, retain the workspace backup and do not delete user data.
4. Launch the recovered version against the same compatible workspace, with only one application using it. Confirm readiness, history, food/workout records and IDs before sending any new requests. Remove obsolete runtime-selection overrides from your launch environment.
5. Reapply a corrected newer release only after its validation. If a future release changes data formats, document and test compatibility before claiming this recovery procedure is safe.

This retirement changes no workspace paths, schemas, IDs, API/tool responses, or protocol version. Automated compatibility checks use temporary data and deterministic providers; they do not substitute for an installer downgrade/upgrade exercise on a clean Windows machine.

## Retirement boundaries

The caller inventory covered production, tests, configuration, frontend launch tests, and active documentation. Useful legacy assertions now live in service/bridge tests, Python tests, and frozen release schema/output fixtures. Java architecture tests reject provider SDK dependencies without an allowlist and keep controller/bridge storage access behind services. Python architecture tests reject direct database-driver imports. These bounded checks supplement review; they do not prove the absence of arbitrary dynamic loading.

Historical implementation details and test totals remain in dated [migration records](AGENT_MIGRATION.md#execution-record), with removed source links pinned to the baseline commit.
